import XCTest

@testable import RecordAgent

final class FinanceInsightsTests: XCTestCase {
    private func period(_ day: String, _ range: FinanceChartRange) throws -> FinanceAnalysisPeriod {
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let parser = FinanceAnalysisPeriod(anchor: Date(), range: .month, timeZone: zone)
        return FinanceAnalysisPeriod(anchor: try XCTUnwrap(parser.date(day)), range: range, timeZone: zone)
    }

    private func transaction(
        _ id: String, date: String, amount: Int64, type: String = "expense",
        category: String = "Food", account: String = "a"
    ) throws -> FinanceTransaction {
        let payload: [String: Any] = [
            "id": id, "accountId": account, "type": type, "category": category,
            "note": "", "amountInCents": amount, "date": date, "time": "12:00:00", "balanceInCents": 0,
        ]
        return try APIClient.decoder.decode(
            FinanceTransaction.self, from: JSONSerialization.data(withJSONObject: payload))
    }

    func testISOWeekSpansMonthsAndYearsAndExcludesNextMonday() throws {
        let period = try period("2026-01-01", .week)
        XCTAssertEqual(period.months, ["2025-12", "2026-01"])
        XCTAssertEqual(period.dayKey(period.start), "2025-12-29")
        XCTAssertEqual(period.dayKey(period.end), "2026-01-05")
        XCTAssertEqual(period.bucketDates.count, 7)
        let entries = try [
            transaction("1", date: "2025-12-29", amount: 29),
            transaction("2", date: "2026-01-04", amount: 50),
            transaction("3", date: "2026-01-05", amount: 999),
        ]
        let result = try FinanceInsights(period: period, entries: entries, accounts: [])
        XCTAssertEqual(result.expense, 79)
        XCTAssertEqual(result.buckets.reduce(0) { $0 + $1.expense }, 79)
    }

    func testYearBucketsRankingsDeduplicationAndIntegerPrecision() throws {
        let period = try period("2024-02-29", .year)
        XCTAssertEqual(period.months.count, 12)
        let first = try transaction("1", date: "2024-02-29", amount: 29)
        let result = try FinanceInsights(
            period: period,
            entries: [
                first, first,
                transaction("2", date: "2024-02-29", amount: 50, category: "Travel"),
                transaction("3", date: "2024-12-31", amount: 90, category: "Food"),
                transaction("4", date: "2024-02-29", amount: 9_007_199_254_740_993, type: "income"),
            ],
            accounts: [FinanceAccount(id: "a", name: "Wallet", balanceInCents: 0, version: 0)])
        XCTAssertEqual(result.buckets.count, 12)
        XCTAssertEqual(result.buckets[1].expense, 79)
        XCTAssertEqual(result.income, 9_007_199_254_740_993)
        XCTAssertEqual(result.expense, 169)
        XCTAssertEqual(result.categories.map(\.amount), [119, 50])
        XCTAssertEqual(result.transactions.map(\.amount), [90, 50, 29])
        XCTAssertEqual(result.accounts.first?.expense, 169)
        XCTAssertEqual(result.accounts.first?.income, result.income)
    }

    func testDayBucketsSurviveDSTAndOverflowFailsInsteadOfWrapping() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        let parser = FinanceAnalysisPeriod(anchor: Date(), range: .month, timeZone: zone)
        let period = FinanceAnalysisPeriod(
            anchor: try XCTUnwrap(parser.date("2024-03-10")), range: .month, timeZone: zone)
        XCTAssertEqual(period.bucketDates.count, 31)
        let result = try FinanceInsights(
            period: period, entries: [transaction("1", date: "2024-03-10", amount: 29)], accounts: [])
        XCTAssertEqual(result.buckets[9].expense, 29)
        let entries = try [
            transaction("1", date: "2024-03-10", amount: Int64.max), transaction("2", date: "2024-03-11", amount: 1),
        ]
        XCTAssertThrowsError(try FinanceInsights(period: period, entries: entries, accounts: []))
    }

    @MainActor
    func testMovingAnchorInsideSameAnalysisPeriodKeepsLoadedChart() async throws {
        let model = FinanceWorkspaceViewModel(
            date: try XCTUnwrap(WorkspaceFormat.date("2024-02-10")), service: DelayedAnalysisService())
        await model.loadInsights()
        let key = try XCTUnwrap(model.visibleInsights?.period.key)
        model.month = try XCTUnwrap(WorkspaceFormat.date("2024-02-29"))
        XCTAssertEqual(model.visibleInsights?.period.key, key)
        XCTAssertFalse(model.isLoadingInsights)
        model.chartRange = .year
        await model.loadInsights()
        let yearKey = try XCTUnwrap(model.visibleInsights?.period.key)
        model.month = try XCTUnwrap(WorkspaceFormat.date("2024-03-01"))
        XCTAssertEqual(model.visibleInsights?.period.key, yearKey)
        XCTAssertFalse(model.isLoadingInsights)
    }

    func testLoaderFetchesEveryPageWithoutListFiltersAndRejectsBrokenPagination() async throws {
        let period = try period("2026-01-01", .month)
        let service = PagedAnalysisService(
            entries: try [
                transaction("1", date: "2026-01-01", amount: 29),
                transaction("2", date: "2026-01-02", amount: 50),
            ])
        let result = try await service.insights(period: period)
        XCTAssertEqual(result.expense, 79)
        XCTAssertEqual(result.accounts.count, 2)
        let calls = await service.calls
        XCTAssertEqual(calls, [0, 1])
        let bad = PagedAnalysisService(entries: [], broken: true)
        do {
            _ = try await bad.insights(period: period)
            XCTFail("Broken pagination must not publish partial totals")
        } catch APIClientError.invalidResponse {}
    }

    @MainActor
    func testOldPeriodResponseCannotReplaceNewRange() async throws {
        let service = DelayedAnalysisService()
        let model = FinanceWorkspaceViewModel(date: try XCTUnwrap(WorkspaceFormat.date("2026-09-30")), service: service)
        let old = Task { await model.loadInsights() }
        while !(await service.started) { await Task.yield() }
        model.chartRange = .week
        await model.loadInsights()
        await old.value
        XCTAssertEqual(model.visibleInsights?.period.range, .week)
        XCTAssertNil(model.insightsError)
    }
}

private actor PagedAnalysisService: FinanceWorkspaceServiceProtocol {
    let entries: [FinanceTransaction]
    let broken: Bool
    var calls: [Int] = []
    init(entries: [FinanceTransaction], broken: Bool = false) {
        self.entries = entries
        self.broken = broken
    }
    func overview(month: String) async throws -> FinanceMonthOverview { throw APIClientError.unavailable }
    func accounts(offset: Int) async throws -> FinanceWorkspacePage<FinanceAccount> {
        FinanceWorkspacePage(
            items: [FinanceAccount(id: offset == 0 ? "a" : "b", name: "Wallet", balanceInCents: 0, version: 0)],
            nextOffset: offset == 0 ? 1 : nil, hasMore: offset == 0)
    }
    func transactions(month: String, type: FinanceTransactionFilter, accountID: String?, query: String, offset: Int)
        async throws -> FinanceWorkspacePage<FinanceTransaction>
    {
        XCTAssertEqual(type, .all)
        XCTAssertNil(accountID)
        XCTAssertEqual(query, "")
        calls.append(offset)
        if broken { return FinanceWorkspacePage(items: [], nextOffset: offset, hasMore: true) }
        return FinanceWorkspacePage(
            items: offset < entries.count ? [entries[offset]] : [],
            nextOffset: offset == 0 ? 1 : nil, hasMore: offset == 0)
    }
}

private actor DelayedAnalysisService: FinanceWorkspaceServiceProtocol {
    var started = false
    func overview(month: String) async throws -> FinanceMonthOverview { throw APIClientError.unavailable }
    func accounts(offset: Int) async throws -> FinanceWorkspacePage<FinanceAccount> {
        FinanceWorkspacePage(items: [], nextOffset: nil, hasMore: false)
    }
    func transactions(month: String, type: FinanceTransactionFilter, accountID: String?, query: String, offset: Int)
        async throws -> FinanceWorkspacePage<FinanceTransaction>
    {
        if !started {
            started = true
            try await Task.sleep(for: .milliseconds(150))
        }
        return FinanceWorkspacePage(items: [], nextOffset: nil, hasMore: false)
    }
}
