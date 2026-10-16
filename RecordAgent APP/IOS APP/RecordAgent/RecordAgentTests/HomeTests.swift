import XCTest

@testable import RecordAgent

final class HomeTests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(secondsFromGMT: 0)!
        value.firstWeekday = 2
        return value
    }
    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    func testMonthSelectionClampsDayForLeapAndCommonYears() {
        let dates = HomeCalendar(calendar: calendar)
        XCTAssertEqual(dates.date(inYear: 2024, month: 2, preservingDayOf: date(2024, 1, 31)), date(2024, 2, 29))
        XCTAssertEqual(dates.date(inYear: 2025, month: 2, preservingDayOf: date(2025, 1, 31)), date(2025, 2, 28))
    }

    func testWeekCrossesYearAndKeepsSevenDistinctDates() {
        let dates = HomeCalendar(calendar: calendar)
        let week = dates.days(inWeek: dates.startOfWeek(containing: date(2027, 1, 1)))
        XCTAssertEqual(week.first, date(2026, 12, 28))
        XCTAssertEqual(week.last, date(2027, 1, 3))
        XCTAssertEqual(Set(week).count, 7)
    }

    func testNaturalDayAcrossDaylightSavingTime() throws {
        var calendar = self.calendar
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let start = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 3, day: 8)))
        let next = HomeCalendar(calendar: calendar).addingDays(1, to: start)
        XCTAssertEqual(calendar.component(.day, from: next), 9)
        XCTAssertEqual(calendar.component(.hour, from: next), 0)
        XCTAssertEqual(next.timeIntervalSince(start), 23 * 3600)
    }

    @MainActor
    func testWeekSwipeAndMonthJumpKeepDateAndCarouselAligned() {
        let now = date(2026, 12, 31)
        let model = HomeViewModel(
            service: MockHomeDataService(now: now, calendar: calendar), now: now, calendar: calendar)
        let nextWeek = model.dates.addingDays(7, to: model.dates.startOfWeek(containing: now))
        model.selectWeek(nextWeek)
        XCTAssertEqual(model.selectedDate, date(2027, 1, 7))
        model.selectDate(date(2027, 1, 31))
        model.selectMonth(year: 2027, month: 2)
        XCTAssertEqual(model.selectedDate, date(2027, 2, 28))
        model.selectMonth(year: 2035, month: 1)
        XCTAssertTrue(model.weekStarts.contains(model.dates.startOfWeek(containing: model.selectedDate)))
    }

    @MainActor
    func testSelectedDateOwnsAllFourCategoriesAndTrend() async throws {
        let now = date(2026, 9, 25)
        let service = MockHomeDataService(now: now, calendar: calendar)
        let model = HomeViewModel(service: service, now: now, calendar: calendar)
        await model.load(locale: Locale(identifier: "zh-Hans"))
        let today = try XCTUnwrap(model.snapshot)
        XCTAssertEqual(Set(today.entries.map { $0.kind.category }), Set(HomeCategory.allCases))
        XCTAssertEqual(today.trend.count, 7)
        XCTAssertEqual(today.trend.last?.expenseInCents, today.expenseInCents)
        XCTAssertEqual(today.trend.last?.focusMinutes, today.focusMinutes)
        XCTAssertEqual(today.completedTodos, today.todos.filter(\.isCompleted).count)
        model.category = .library
        XCTAssertEqual(model.visibleEntries.count, today.documentCount)
        model.selectDate(date(2026, 9, 24))
        XCTAssertNil(model.snapshot, "不能把旧日内容标记为新日期")
        await model.load(locale: Locale(identifier: "zh-Hans"))
        let yesterday = try XCTUnwrap(model.snapshot)
        XCTAssertEqual(yesterday.date, date(2026, 9, 24))
        XCTAssertTrue(yesterday.entries.allSatisfy { calendar.isDate($0.date, inSameDayAs: yesterday.date) })
        XCTAssertNotEqual(today.expenseInCents, yesterday.expenseInCents)
        XCTAssertTrue(Set(today.entries.map(\.id)).isDisjoint(with: yesterday.entries.map(\.id)))
    }

    @MainActor
    func testTodoCompletionSurvivesDateAndViewModelSwitches() async throws {
        let now = date(2026, 9, 25)
        let service = MockHomeDataService(now: now, calendar: calendar)
        let model = HomeViewModel(service: service, now: now, calendar: calendar)
        await model.load(locale: .current)
        let snapshot = try XCTUnwrap(model.snapshot)
        let todo = try XCTUnwrap(snapshot.todos.first(where: { !$0.isCompleted }))
        await model.toggleTodo(todo)
        XCTAssertEqual(model.snapshot?.completedTodos, snapshot.completedTodos + 1)
        model.selectDate(date(2026, 9, 24))
        await model.load(locale: .current)
        XCTAssertEqual(model.snapshot?.completedTodos, 1)
        let rebuilt = HomeViewModel(service: service, now: now, calendar: calendar)
        await rebuilt.load(locale: .current)
        XCTAssertEqual(rebuilt.snapshot?.completedTodos, snapshot.completedTodos + 1)
        let updated = try XCTUnwrap(rebuilt.snapshot?.entries.first(where: { $0.id == todo.id }))
        await rebuilt.toggleTodo(updated)
        XCTAssertEqual(rebuilt.snapshot?.completedTodos, snapshot.completedTodos)
    }

    @MainActor
    func testFutureDateHasHonestEmptyState() async throws {
        let now = date(2026, 9, 25)
        let model = HomeViewModel(
            service: MockHomeDataService(now: now, calendar: calendar), now: now, calendar: calendar)
        model.selectDate(date(2026, 10, 10))
        await model.load(locale: .current)
        let snapshot = try XCTUnwrap(model.snapshot)
        XCTAssertTrue(snapshot.entries.isEmpty)
        XCTAssertEqual(snapshot.expenseInCents, 0)
        XCTAssertTrue(snapshot.trend.allSatisfy { $0.expenseInCents == 0 && $0.focusMinutes == 0 })
        XCTAssertFalse(model.loadFailed)
    }

    @MainActor
    func testUnavailableServiceShowsFailureInsteadOfFakeZero() async {
        let model = HomeViewModel(service: UnavailableHomeDataService())
        await model.load(locale: .current)
        XCTAssertTrue(model.loadFailed)
        XCTAssertFalse(model.isLoading)
        XCTAssertNil(model.snapshot)
    }

    func testSampleTextUsesRequestedLocale() async throws {
        let now = date(2026, 9, 25)
        let service = MockHomeDataService(now: now, calendar: calendar)
        let zh = try await service.snapshot(for: now, locale: Locale(identifier: "zh-Hans"))
        let en = try await service.snapshot(for: now, locale: Locale(identifier: "en"))
        XCTAssertNotEqual(zh.entries.first?.title, en.entries.first?.title)
        XCTAssertEqual(zh.entries.map(\.id), en.entries.map(\.id))
    }

    func testMinuteFormatFollowsAppLanguage() {
        XCTAssertEqual(HomeFormat.minutes(45, locale: Locale(identifier: "zh-Hans")), "45 分钟")
        XCTAssertEqual(HomeFormat.minutes(45, locale: Locale(identifier: "en")), "45 min")
    }

    func testWeekRangeStopsAtSupportedCalendarBoundaries() {
        let dates = HomeCalendar(calendar: calendar)
        XCTAssertTrue(dates.weeks(around: dates.supportedDates.lowerBound).allSatisfy(dates.isSupportedWeek))
        XCTAssertTrue(dates.weeks(around: dates.supportedDates.upperBound).allSatisfy(dates.isSupportedWeek))
    }

    @MainActor
    func testFailedWriteKeepsCompletionState() async throws {
        let now = date(2026, 9, 25)
        let source = MockHomeDataService(now: now, calendar: calendar)
        let original = try await source.snapshot(for: now, locale: .current)
        let model = HomeViewModel(service: FailedWriteHomeService(value: original), now: now, calendar: calendar)
        await model.load(locale: .current)
        let todo = try XCTUnwrap(original.todos.first)
        await model.toggleTodo(todo)
        XCTAssertEqual(model.snapshot, original)
        XCTAssertTrue(model.showWriteError)
        XCTAssertTrue(model.savingTodoIDs.isEmpty)
    }

    @MainActor
    func testLateResponseCannotReplaceNewDate() async throws {
        let firstDate = date(2026, 9, 24)
        let secondDate = date(2026, 9, 25)
        let firstRequested = expectation(description: "first date requested")
        let secondRequested = expectation(description: "second date requested")
        let service = ControlledHomeService { date in
            if date == firstDate { firstRequested.fulfill() }
            if date == secondDate { secondRequested.fulfill() }
        }
        let model = HomeViewModel(service: service, now: firstDate, calendar: calendar)
        let firstTask = Task { await model.load(locale: .current) }
        await fulfillment(of: [firstRequested], timeout: 2)
        model.selectDate(secondDate)
        let secondTask = Task { await model.load(locale: .current) }
        await fulfillment(of: [secondRequested], timeout: 2)
        await service.complete(secondDate)
        await secondTask.value
        await service.complete(firstDate)
        await firstTask.value
        XCTAssertEqual(model.snapshot?.date, secondDate)
        XCTAssertFalse(model.isLoading)
    }
}

/// 显式控制响应顺序，验证快速切换日期的过期响应隔离。
private actor ControlledHomeService: HomeDataServiceProtocol {
    let onRequest: @Sendable (Date) -> Void
    var requests: [Date: CheckedContinuation<HomeDaySnapshot, Error>] = [:]
    init(onRequest: @escaping @Sendable (Date) -> Void) { self.onRequest = onRequest }
    func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot {
        try await withCheckedThrowingContinuation { continuation in
            requests[date] = continuation
            onRequest(date)
        }
    }
    func complete(_ date: Date) {
        requests.removeValue(forKey: date)?.resume(
            returning: HomeDaySnapshot(date: date, entries: [], trend: [], isDemo: true))
    }
    func setTodoCompleted(id: String, completed: Bool, date: Date) async throws {
        throw HomeDataError.unavailable
    }
}

private struct FailedWriteHomeService: HomeDataServiceProtocol {
    let value: HomeDaySnapshot
    func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot { value }
    func setTodoCompleted(id: String, completed: Bool, date: Date) async throws { throw HomeDataError.unavailable }
}
