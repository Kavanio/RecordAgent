import UIKit
import XCTest

@testable import RecordAgent

final class FinanceLibraryWorkspaceTests: XCTestCase {
    func testAmountConversionHasNoFloatingPointRoundingAndRejectsOverflow() {
        XCTAssertEqual(FinanceAmountInput.cents("0.29"), 29)
        XCTAssertEqual(FinanceAmountInput.cents("12,34"), 1234)
        XCTAssertEqual(FinanceAmountInput.cents(".50"), 50)
        XCTAssertEqual(FinanceAmountInput.cents("0", allowsZero: true), 0)
        XCTAssertNil(FinanceAmountInput.cents("0"))
        XCTAssertNil(FinanceAmountInput.cents("-1"))
        XCTAssertNil(FinanceAmountInput.cents("1.234"))
        XCTAssertNil(FinanceAmountInput.cents("92233720368547758.08"))
        XCTAssertEqual(FinanceAmountInput.cents("92233720368547758.07"), Int64.max)
    }

    func testTransactionDTOSeparatesNaturalDateTimeAndIntegerMoney() throws {
        let data = Data(
            """
            {"items":[{"id":"tx","accountId":"account","type":"expense","category":null,"note":"","amountInCents":29,"date":"2024-02-29","time":"09:45:30","balanceInCents":9007199254740993}],"nextOffset":50,"hasMore":true}
            """.utf8)
        let page = try APIClient.decoder.decode(FinanceWorkspacePage<FinanceTransaction>.self, from: data)
        let item = try XCTUnwrap(page.items.first)
        XCTAssertEqual(item.amountInCents, 29)
        XCTAssertEqual(item.category, "")
        XCTAssertEqual(item.balanceInCents, 9_007_199_254_740_993)
        XCTAssertNil(item.balanceAfterInCents)
        XCTAssertEqual(item.date, "2024-02-29")
        XCTAssertEqual(item.time, "09:45:30")
        XCTAssertEqual(page.nextOffset, 50)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let time = try XCTUnwrap(FinanceTransactionTime.date(item.time, calendar: calendar))
        XCTAssertEqual(calendar.component(.hour, from: time), 9)
        XCTAssertEqual(calendar.component(.minute, from: time), 45)
        XCTAssertNil(FinanceTransactionTime.date("24:00:00"))
        XCTAssertNil(FinanceTransactionTime.date("09:45:99"))
    }

    func testBalanceSnapshotIsIndependentOfCurrentBalanceAndCategoryIconsExist() throws {
        let item = try APIClient.decoder.decode(
            FinanceTransaction.self,
            from: Data(
                """
                {"id":"tx","accountId":"a","type":"expense","category":"交通","note":"","amountInCents":300,"date":"2026-09-30","time":"09:00:00","balanceInCents":8700,"balanceAfterInCents":9700}
                """.utf8))
        XCTAssertEqual(item.balanceAfterInCents, 9700)
        XCTAssertEqual(item.balanceInCents, 8700)
        XCTAssertEqual(FinanceCategoryCatalog.symbol(category: "交通", type: "expense"), "tram.fill")
        XCTAssertEqual(FinanceCategoryCatalog.symbol(category: "奶茶", type: "expense"), "mug.fill")
        for preset in FinanceCategoryCatalog.expenses + FinanceCategoryCatalog.incomes {
            XCTAssertNotNil(UIImage(systemName: preset.symbol), preset.name)
            let key = "finance.category.preset." + preset.key
            for language in ["zh-Hans", "en"] {
                XCTAssertNotEqual(
                    AppLocalization.string(
                        String.LocalizationValue(stringLiteral: key), locale: Locale(identifier: language)), key)
            }
        }
        XCTAssertEqual(FinanceCategoryCatalog.symbol(category: "自定义", type: "expense"), "tag.fill")
    }

    @MainActor
    func testMonthAndFiltersInvalidateRowsAndCursorBeforeDebouncedReload() async throws {
        let service = FinanceSwitchingService()
        let model = FinanceWorkspaceViewModel(date: try XCTUnwrap(WorkspaceFormat.date("2024-02-29")), service: service)
        await model.loadOverview()
        await model.loadTransactions(reset: true)
        XCTAssertNotNil(model.overview)
        XCTAssertEqual(model.nextTransactionOffset, 50)
        model.month = try XCTUnwrap(WorkspaceFormat.date("2024-03-01"))
        XCTAssertNil(model.overview)
        XCTAssertTrue(model.transactions.isEmpty)
        XCTAssertNil(model.nextTransactionOffset)
        XCTAssertTrue(model.isLoadingTransactions)
        await model.loadTransactions(reset: false)
        let callsBeforeReload = await service.offsets
        XCTAssertEqual(callsBeforeReload, [0])
        await model.loadTransactionsIfNeeded()
        XCTAssertEqual(model.transactions.first?.date, "2024-03-01")
        model.type = .income
        XCTAssertTrue(model.transactions.isEmpty)
        XCTAssertNil(model.nextTransactionOffset)
        await model.loadTransactionsIfNeeded()
        model.selectedAccountID = "wallet"
        XCTAssertTrue(model.transactions.isEmpty)
        await model.loadTransactionsIfNeeded()
        model.query = "coffee"
        XCTAssertTrue(model.transactions.isEmpty)
        XCTAssertNil(model.nextTransactionOffset)
    }

    @MainActor
    func testOldOverviewCannotWinAfterSwitchingAwayAndBack() async throws {
        let service = FinanceSwitchingService(blockFirstOverview: true)
        let february = try XCTUnwrap(WorkspaceFormat.date("2024-02-29"))
        let model = FinanceWorkspaceViewModel(date: february, service: service)
        let old = Task { await model.loadOverview() }
        while !(await service.startedOverview) { await Task.yield() }
        model.month = try XCTUnwrap(WorkspaceFormat.date("2024-03-01"))
        await model.loadOverview()
        model.month = february
        await model.loadOverview()
        await service.releaseOverview()
        await old.value
        XCTAssertEqual(model.overview?.expenseInCents, 300)
        XCTAssertFalse(model.isLoadingOverview)
    }

    @MainActor
    func testCanceledOverviewResponseIsNotPublished() async throws {
        let service = FinanceSwitchingService(blockFirstOverview: true)
        let model = FinanceWorkspaceViewModel(date: Date(), service: service)
        let task = Task { await model.loadOverview() }
        while !(await service.startedOverview) { await Task.yield() }
        task.cancel()
        await service.releaseOverview()
        await task.value
        XCTAssertNil(model.overview)
        XCTAssertNil(model.overviewError)
        XCTAssertFalse(model.isLoadingOverview)
    }

    func testLibraryDraftEncodesStableIdentityVersionAndFullBody() throws {
        let document = try APIClient.decoder.decode(
            LibraryDocument.self,
            from: Data(
                """
                {"id":"c75231b1-c18d-4f94-aa22-2b1885b55b26","date":"2026-09-30","type":"journal","title":"Today","body":"First line\\nSecond line","favorite":true,"trashed":false,"version":7,"createdAt":"2026-09-30T09:27:35.123Z","updatedAt":"2026-09-30T10:30:00Z"}
                """.utf8))
        let draft = LibraryDraft(document: document, timeZone: "Asia/Shanghai")
        let payload = try XCTUnwrap(
            JSONSerialization.jsonObject(with: APIClient.encoder.encode(draft)) as? [String: Any])
        XCTAssertEqual(payload["id"] as? String, document.id)
        XCTAssertEqual(payload["version"] as? Int, 7)
        XCTAssertEqual(payload["body"] as? String, "First line\nSecond line")
        XCTAssertEqual(payload["timeZone"] as? String, "Asia/Shanghai")
        XCTAssertNotNil(document.createdAt)
        XCTAssertNotNil(document.updatedAt)
        XCTAssertNotEqual(document.createdAt, document.updatedAt)
        XCTAssertNil(payload["createdAt"])
        XCTAssertNil(payload["updatedAt"])
        var tooLong = draft
        tooLong.title = String(repeating: "😀", count: 121)
        XCTAssertFalse(tooLong.isValid)
    }

    @MainActor
    func testVersionConflictAndComparisonKeepDraftUntilExplicitChoice() async throws {
        let latest = LibraryDocument(
            id: "old", date: "2026-09-30", type: .quick,
            title: "Server", body: "New server text", favorite: false, trashed: false, version: 8)
        let draft = LibraryDraft(id: "old", date: "2026-09-30", title: "Draft", body: "My unsaved text", version: 7)
        let model = LibraryEditorViewModel(
            draft: draft, creating: false, service: ConflictingLibraryService(latest: latest))
        do {
            _ = try await model.save()
            XCTFail("A stale version must fail")
        } catch APIClientError.conflict {}
        await model.loadLatest()
        XCTAssertEqual(model.draft, draft)
        XCTAssertEqual(model.latest?.version, 8)
        XCTAssertTrue(model.hasConflict)
        model.keepDraftAsNew()
        XCTAssertNotEqual(model.draft.id, draft.id)
        XCTAssertEqual(model.draft.version, 0)
        XCTAssertEqual(model.draft.body, draft.body)
        XCTAssertTrue(model.creating)
        XCTAssertFalse(model.hasConflict)
    }
}

private struct ConflictingLibraryService: LibraryServiceProtocol {
    let latest: LibraryDocument
    func page(type: LibraryDocumentKind?, collection: LibraryCollection, query: String, offset: Int) async throws
        -> LibraryPage
    {
        LibraryPage(items: [], nextOffset: nil, hasMore: false)
    }
    func document(id: String) async throws -> LibraryDocument { latest }
    func save(_ draft: LibraryDraft, creating: Bool) async throws -> LibraryDocument { throw APIClientError.conflict }
    func flags(id: String, version: Int64, favorite: Bool, trashed: Bool) async throws -> LibraryDocument {
        throw APIClientError.conflict
    }
}

/// 可控响应门用于验证切月和取消，而不是依赖网络延迟或时间猜测。
private actor FinanceSwitchingService: FinanceWorkspaceServiceProtocol {
    var offsets: [Int] = []
    var startedOverview = false
    private let blockFirstOverview: Bool
    private var overviewCalls: Int64 = 0
    private var pendingOverview: CheckedContinuation<Void, Never>?

    init(blockFirstOverview: Bool = false) { self.blockFirstOverview = blockFirstOverview }

    func overview(month: String) async throws -> FinanceMonthOverview {
        overviewCalls += 1
        let call = overviewCalls
        if blockFirstOverview && call == 1 {
            startedOverview = true
            await withCheckedContinuation { pendingOverview = $0 }
        }
        return FinanceMonthOverview(totalAssetsInCents: 1000, expenseInCents: call * 100, incomeInCents: 0, transactionCount: 1)
    }

    func releaseOverview() {
        pendingOverview?.resume()
        pendingOverview = nil
    }

    func accounts(offset: Int) async throws -> FinanceWorkspacePage<FinanceAccount> {
        FinanceWorkspacePage(items: [], nextOffset: nil, hasMore: false)
    }

    func transactions(month: String, type: FinanceTransactionFilter, accountID: String?, query: String, offset: Int)
        async throws -> FinanceWorkspacePage<FinanceTransaction> {
        offsets.append(offset)
        let payload: [String: Any] = [
            "id": month, "accountId": "wallet", "type": type == .income ? "income" : "expense",
            "category": "Food", "note": "", "amountInCents": 100,
            "date": month + "-01", "time": "12:00:00", "balanceInCents": 1000
        ]
        let row = try APIClient.decoder.decode(FinanceTransaction.self, from: JSONSerialization.data(withJSONObject: payload))
        return FinanceWorkspacePage(items: [row], nextOffset: 50, hasMore: true)
    }
}
