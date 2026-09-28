import XCTest

@testable import RecordAgent

final class CalendarTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }
    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    func testTimelineGapsReflectElapsedMinutesAndLimitLongEmptyPeriods() {
        let morning = date(2026, 9, 26).addingTimeInterval(9 * 3600)
        let halfHour = morning.addingTimeInterval(30 * 60)
        let noon = morning.addingTimeInterval(3 * 3600)
        let evening = morning.addingTimeInterval(12 * 3600)
        let near = CalendarTimelineSpacing.gap(between: morning, and: halfHour)
        let middle = CalendarTimelineSpacing.gap(between: morning, and: noon)
        let far = CalendarTimelineSpacing.gap(between: morning, and: evening)
        XCTAssertEqual(near, 12)
        XCTAssertLessThan(near, middle)
        XCTAssertLessThan(middle, far)
        XCTAssertEqual(far, 124)
        XCTAssertEqual(CalendarTimelineSpacing.gap(between: morning, and: morning), 12)
    }

    func testMonthGridIncludesWholeMondayWeeksAcrossYearAndLeapDay() {
        let dates = HomeCalendar(calendar: calendar)
        let january = dates.monthGrid(containing: date(2027, 1, 12))
        XCTAssertEqual(january.count, 35)
        XCTAssertEqual(january.first, date(2026, 12, 28))
        XCTAssertEqual(january.last, date(2027, 1, 31))
        let leap = dates.monthGrid(containing: date(2024, 2, 10))
        XCTAssertTrue(leap.contains(date(2024, 2, 29)))
        XCTAssertEqual(Set(leap).count, leap.count)
        XCTAssertEqual(dates.monthGrid(containing: date(2026, 3, 1)).count, 42)
        XCTAssertEqual(dates.monthGrid(containing: date(2021, 2, 1)).count, 28)
    }

    func testMonthNavigationClampsAndRespectsSupportedRange() {
        let dates = HomeCalendar(calendar: calendar)
        XCTAssertEqual(dates.shiftingMonth(1, from: date(2024, 1, 31)), date(2024, 2, 29))
        XCTAssertEqual(dates.shiftingMonth(-1, from: date(2027, 1, 31)), date(2026, 12, 31))
        XCTAssertNil(dates.shiftingMonth(-1, from: dates.supportedDates.lowerBound))
        XCTAssertNil(dates.shiftingMonth(1, from: dates.supportedDates.upperBound))
    }

    func testGridUsesNaturalDaysAcrossDaylightSavingChange() throws {
        var local = calendar
        local.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let month = try XCTUnwrap(local.date(from: DateComponents(year: 2026, month: 3, day: 8)))
        let dates = HomeCalendar(calendar: local).monthGrid(containing: month)
        XCTAssertEqual(Set(dates).count, dates.count)
        XCTAssertTrue(dates.allSatisfy { local.component(.hour, from: $0) == 0 })
    }

    func testMonthlyNoteAndLastDayUseSameInstanceAndCompletion() async throws {
        let today = date(2026, 9, 26)
        let service = MockHomeDataService(now: today, calendar: calendar)
        let groups = try await service.periodicTodos(for: today, locale: .current)
        let group = try XCTUnwrap(groups.first { $0.period == .monthly })
        let todo = try XCTUnwrap(group.entries.last)
        XCTAssertEqual(todo.date, date(2026, 9, 30))
        let before = try await service.snapshot(for: date(2026, 9, 29), locale: .current)
        XCTAssertFalse(before.entries.contains { $0.id == todo.id })
        try await service.setTodoCompleted(id: todo.id, completed: true, date: todo.date)
        let lastDay = try await service.snapshot(for: todo.date, locale: .current)
        XCTAssertTrue(try XCTUnwrap(lastDay.entries.first { $0.id == todo.id }).isCompleted)
        let updated = try await service.periodicTodos(for: today, locale: .current)
        XCTAssertEqual(updated.first { $0.period == .monthly }?.completedCount, group.completedCount + 1)
        let next = try await service.periodicTodos(for: date(2026, 10, 2), locale: .current)
        XCTAssertEqual(next.first { $0.period == .monthly }?.completedCount, 0)
        XCTAssertFalse(next.flatMap(\.entries).contains { $0.id == todo.id })
    }

    func testCreateAppearsInHomeTimelineAndMonthMarkers() async throws {
        let today = date(2026, 9, 26)
        let future = date(2026, 10, 10)
        let service = MockHomeDataService(now: today, calendar: calendar)
        try await service.createEntry(CalendarEntryDraft(kind: .schedule, title: "项目讨论", date: future))
        let day = try await service.snapshot(for: future, locale: .current)
        XCTAssertEqual(day.entries.map(\.title), ["项目讨论"])
        let month = try await service.month(for: future, locale: .current)
        XCTAssertEqual(month.days.first { $0.date == future }?.kinds, [.schedule])
        XCTAssertTrue(month.days.allSatisfy { $0.kinds.count <= 3 })
    }

    func testWeeklyCreationStartsInChosenPeriodAndUsesSunday() async throws {
        let today = date(2026, 9, 26)
        let service = MockHomeDataService(now: today, calendar: calendar)
        try await service.createEntry(CalendarEntryDraft(title: "周计划", date: date(2026, 10, 5), period: .weekly))
        let previous = try await service.periodicTodos(for: today, locale: .current)
        XCTAssertFalse(previous.flatMap(\.entries).contains { $0.title == "周计划" })
        let following = try await service.periodicTodos(for: date(2026, 10, 7), locale: .current)
        let todo = try XCTUnwrap(following.flatMap(\.entries).first { $0.title == "周计划" })
        XCTAssertEqual(todo.date, date(2026, 10, 11))
    }

    func testBlankTitleIsRejectedWithoutCreatingData() async throws {
        let today = date(2026, 9, 26)
        let service = MockHomeDataService(now: today, calendar: calendar)
        do {
            try await service.createEntry(CalendarEntryDraft(title: "  \n ", date: today))
            XCTFail("空标题必须被拒绝")
        } catch HomeDataError.invalidEntry {} catch { XCTFail("意外错误：\(error)") }
    }

    @MainActor
    func testUnavailableServiceReportsEachFailedSectionAndWrite() async {
        let model = CalendarViewModel(service: UnavailableHomeDataService())
        await model.load(for: .now, locale: .current)
        XCTAssertTrue(model.monthFailed)
        XCTAssertTrue(model.notesFailed)
        XCTAssertFalse(model.isLoading)
        XCTAssertNil(model.month)
        XCTAssertTrue(model.groups.isEmpty)
        let saved = await model.create(CalendarEntryDraft(title: "待办", date: .now))
        XCTAssertFalse(saved)
        XCTAssertTrue(model.showWriteError)
        XCTAssertFalse(model.isCreating)
    }

    @MainActor
    func testLateMonthResponseCannotReplaceSelectedMonth() async {
        let first = date(2026, 9, 1)
        let second = date(2026, 10, 1)
        let requested = expectation(description: "old month requested")
        let service = DeferredCalendarService(delayedDate: first, onRequest: { requested.fulfill() })
        let model = CalendarViewModel(service: service)
        let old = Task { await model.load(for: first, locale: .current) }
        await fulfillment(of: [requested], timeout: 2)
        await model.load(for: second, locale: .current)
        await service.finish()
        await old.value
        XCTAssertEqual(model.month?.month, second)
        XCTAssertFalse(model.isLoading)
    }

    func testCalendarSamplesFollowRequestedLanguage() async throws {
        let today = date(2026, 9, 26)
        let service = MockHomeDataService(now: today, calendar: calendar)
        let chinese = try await service.periodicTodos(for: today, locale: Locale(identifier: "zh-Hans"))
        let english = try await service.periodicTodos(for: today, locale: Locale(identifier: "en"))
        XCTAssertNotEqual(chinese.first?.entries.first?.title, english.first?.entries.first?.title)
        XCTAssertEqual(chinese.flatMap(\.entries).map(\.id), english.flatMap(\.entries).map(\.id))
    }
}

private actor DeferredCalendarService: CalendarDataServiceProtocol {
    let delayedDate: Date
    let onRequest: @Sendable () -> Void
    private var continuation: CheckedContinuation<CalendarMonthSnapshot, Error>?
    init(delayedDate: Date, onRequest: @escaping @Sendable () -> Void) {
        self.delayedDate = delayedDate
        self.onRequest = onRequest
    }
    func month(for date: Date, locale: Locale) async throws -> CalendarMonthSnapshot {
        if date != delayedDate { return CalendarMonthSnapshot(month: date, days: []) }
        return try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            onRequest()
        }
    }
    func finish() {
        continuation?.resume(returning: CalendarMonthSnapshot(month: delayedDate, days: []))
        continuation = nil
    }
    func periodicTodos(for date: Date, locale: Locale) async throws -> [CalendarTodoGroup] { [] }
    func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot {
        HomeDaySnapshot(date: date, entries: [], trend: [], isDemo: false)
    }
    func setTodoCompleted(id: String, completed: Bool, date: Date) async throws {}
    func createEntry(_ draft: CalendarEntryDraft) async throws {}
}
