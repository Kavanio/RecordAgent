import XCTest

@testable import RecordAgent

final class CalendarTests: XCTestCase {
    func testFinancialRetryPreservesKeyUntilSuccessOrContentChanges() {
        var submission = FinanceSubmissionState()
        let body = Data("same request".utf8)
        let first = UUID()
        XCTAssertEqual(submission.key(for: body, preferred: first), first)
        XCTAssertEqual(submission.key(for: body, preferred: UUID()), first)
        submission.completed(first)
        let next = UUID()
        XCTAssertEqual(submission.key(for: body, preferred: next), next)
        let changed = UUID()
        XCTAssertEqual(submission.key(for: Data("changed request".utf8), preferred: changed), changed)
        submission.completed(next)
        XCTAssertEqual(submission.key(for: Data("changed request".utf8), preferred: UUID()), changed)
    }

    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }
    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day))!
    }

    func testDailyTodosStartOnSelectedDayAndCompletionIsIndependent() async throws {
        let start = date(2026, 10, 1)
        let next = date(2026, 10, 2)
        let service = MockHomeDataService(now: date(2026, 9, 26), calendar: calendar)
        try await service.createEntry(
            CalendarEntryDraft(title: "每日阅读", date: start, period: .daily))
        try await service.createEntry(CalendarEntryDraft(title: "一次性待办", date: start))
        let before = try await service.snapshot(for: date(2026, 9, 30), locale: .current)
        XCTAssertFalse(before.entries.contains { $0.title == "每日阅读" })
        let first = try await service.snapshot(for: start, locale: .current)
        let daily = try XCTUnwrap(first.entries.first { $0.title == "每日阅读" })
        XCTAssertTrue(first.entries.contains { $0.title == "一次性待办" })
        try await service.setTodoCompleted(id: daily.id, completed: true, date: start)
        let completed = try await service.snapshot(for: start, locale: .current)
        XCTAssertTrue(try XCTUnwrap(completed.entries.first { $0.id == daily.id }).isCompleted)
        let following = try await service.snapshot(for: next, locale: .current)
        let nextDaily = try XCTUnwrap(following.entries.first { $0.title == "每日阅读" })
        XCTAssertFalse(nextDaily.isCompleted)
        XCTAssertNotEqual(nextDaily.id, daily.id)
        XCTAssertFalse(following.entries.contains { $0.title == "一次性待办" })
        let notes = try await service.periodicTodos(for: start, locale: .current)
        XCTAssertFalse(notes.contains { $0.period == .daily })
    }

    func testDailyPeriodUsesNaturalDaysAcrossDaylightSaving() throws {
        var local = calendar
        local.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        let start = try XCTUnwrap(local.date(from: DateComponents(year: 2026, month: 3, day: 8)))
        let next = try XCTUnwrap(local.date(byAdding: .day, value: 1, to: start))
        let dates = HomeCalendar(calendar: local)
        XCTAssertEqual(dates.periodEnd(.daily, containing: start), start)
        XCTAssertEqual(dates.periodEnd(.daily, containing: next), next)
        XCTAssertEqual(next.timeIntervalSince(start), 23 * 3600)
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

    func testNoteMotionIsBoundedEvenForLargeSensorReadings() {
        let strong = CalendarNoteMotion.response(
            gravityX: 1, angularVelocityX: 8, angularVelocityZ: 8,
            accelerationX: 4, accelerationY: -4)
        XCTAssertEqual(strong.rotation, 6)
        XCTAssertEqual(strong.lift, 5)
        let opposite = CalendarNoteMotion.response(
            gravityX: -1, angularVelocityX: -8, angularVelocityZ: -8,
            accelerationX: -4, accelerationY: 4)
        XCTAssertEqual(opposite.rotation, -6)
        XCTAssertEqual(opposite.lift, -5)
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
        XCTAssertEqual(
            updated.first { $0.period == .monthly }?.completedCount, group.completedCount + 1)
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

    func testPastPhotoRecordSuppliesMonthThumbnail() async throws {
        let today = date(2026, 9, 26)
        let past = date(2026, 9, 24)
        let service = MockHomeDataService(now: today, calendar: calendar)
        let day = try await service.snapshot(for: past, locale: .current)
        let photo = try XCTUnwrap(day.entries.first { $0.kind == .photo })
        let month = try await service.month(for: today, locale: .current)
        XCTAssertEqual(
            month.days.first { $0.date == past }?.sampleImageName, photo.sampleImageNames.first)
        XCTAssertNotNil(month.days.first { $0.date == past }?.sampleImageName)
    }

    func testWeeklyCreationStartsInChosenPeriodAndUsesSunday() async throws {
        let today = date(2026, 9, 26)
        let service = MockHomeDataService(now: today, calendar: calendar)
        try await service.createEntry(
            CalendarEntryDraft(title: "周计划", date: date(2026, 10, 5), period: .weekly))
        let previous = try await service.periodicTodos(for: today, locale: .current)
        XCTAssertFalse(previous.flatMap(\.entries).contains { $0.title == "周计划" })
        let following = try await service.periodicTodos(for: date(2026, 10, 7), locale: .current)
        let todo = try XCTUnwrap(following.flatMap(\.entries).first { $0.title == "周计划" })
        XCTAssertEqual(todo.date, date(2026, 10, 11))
    }

    func testSelectedWeekdayAppearsInTimelineAndWholeWeekNote() async throws {
        let today = date(2026, 9, 26)
        let service = MockHomeDataService(now: today, calendar: calendar)
        let monday = date(2026, 10, 5)
        for weekday in 1...7 {
            try await service.createEntry(CalendarEntryDraft(
                title: "星期待办 \(weekday)", date: monday, period: .weekly, recurrenceWeekday: weekday))
        }
        let notes = try await service.periodicTodos(for: date(2026, 10, 7), locale: .current)
        let weekly = try XCTUnwrap(notes.first { $0.period == .weekly })
        let created = weekly.entries.filter { $0.title.hasPrefix("星期待办") }
        XCTAssertEqual(created.count, 7)
        for weekday in 1...7 {
            let occurrence = HomeCalendar(calendar: calendar).addingDays(weekday - 1, to: monday)
            let snapshot = try await service.snapshot(for: occurrence, locale: .current)
            XCTAssertEqual(snapshot.entries.filter { $0.title.hasPrefix("星期待办") }.map(\.title),
                           ["星期待办 \(weekday)"])
            XCTAssertEqual(created.first { $0.title == "星期待办 \(weekday)" }?.date, occurrence)
        }
        let wednesday = try XCTUnwrap(created.first { $0.title == "星期待办 3" })
        try await service.setTodoCompleted(id: wednesday.id, completed: true, date: date(2026, 10, 7))
        let updated = try await service.periodicTodos(for: monday, locale: .current)
        XCTAssertTrue(try XCTUnwrap(updated.flatMap(\.entries).first { $0.id == wednesday.id }).isCompleted)
        let following = try await service.snapshot(for: date(2026, 10, 14), locale: .current)
        XCTAssertFalse(try XCTUnwrap(following.entries.first { $0.title == "星期待办 3" }).isCompleted)
    }

    func testWeeklyStartSkipsElapsedWeekdayAndMonthlyUsesLeapMonthEnd() async throws {
        let service = MockHomeDataService(now: date(2024, 1, 1), calendar: calendar)
        try await service.createEntry(CalendarEntryDraft(title: "周一待办", date: date(2024, 2, 28),
                                                        period: .weekly, recurrenceWeekday: 1))
        try await service.createEntry(CalendarEntryDraft(title: "月末待办", date: date(2024, 2, 28), period: .monthly))
        let notes = try await service.periodicTodos(for: date(2024, 2, 28), locale: .current)
        XCTAssertFalse(notes.flatMap(\.entries).contains { $0.title == "周一待办" })
        let monthEnd = try await service.snapshot(for: date(2024, 2, 29), locale: .current)
        XCTAssertTrue(monthEnd.entries.contains { $0.title == "月末待办" })
        let nextMonday = try await service.snapshot(for: date(2024, 3, 4), locale: .current)
        XCTAssertTrue(nextMonday.entries.contains { $0.title == "周一待办" })
        XCTAssertFalse(CalendarEntryDraft(date: date(2024, 2, 28), period: .weekly).hasSpecificTime)
        XCTAssertFalse(CalendarEntryDraft(date: date(2024, 2, 28), period: .monthly).hasSpecificTime)
        XCTAssertTrue(CalendarEntryDraft(date: date(2024, 2, 28), period: .daily).hasSpecificTime)
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
