import Foundation

#if DEBUG
    /// 可控开发假服务：日期决定样例内容，待办修改在本次会话内按日期保存。
    actor MockHomeDataService: CalendarDataServiceProtocol {
        private let calendar: Calendar
        private let today: Date
        private var completedOverrides: [String: Bool] = [:]
        private var createdEntries: [HomeEntry] = []
        private var recurringDrafts: [(id: String, draft: CalendarEntryDraft)] = []

        init(now: Date = .now, calendar: Calendar = .current) {
            self.calendar = calendar
            self.today = calendar.startOfDay(for: now)
        }

        func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot {
            try Task.checkCancellation()
            let day = calendar.startOfDay(for: date)
            let dates = HomeCalendar(calendar: calendar)
            let trend = (-6...0).map { offset in
                let date = dates.addingDays(offset, to: day)
                let items = entries(for: date, locale: locale)
                return HomeTrendPoint(
                    date: date,
                    expenseInCents: items.filter { $0.kind == .expense }.reduce(0) { $0 + $1.amountInCents },
                    focusMinutes: items.filter { $0.kind == .focus }.reduce(0) { $0 + $1.durationMinutes }
                )
            }
            return HomeDaySnapshot(
                date: day, entries: entries(for: day, locale: locale), trend: trend, isDemo: true)
        }

        func setTodoCompleted(id: String, completed: Bool, date: Date) async throws {
            try Task.checkCancellation()
            guard
                entries(for: calendar.startOfDay(for: date), locale: .current)
                    .contains(where: { $0.id == id && $0.kind == .todo })
            else { throw HomeDataError.missingTodo }
            completedOverrides[id] = completed
        }

        func month(for date: Date, locale: Locale) async throws -> CalendarMonthSnapshot {
            try Task.checkCancellation()
            let dates = HomeCalendar(calendar: calendar)
            return CalendarMonthSnapshot(
                month: dates.startOfMonth(containing: date),
                days: dates.monthGrid(containing: date).map { day in
                    let items = entries(for: day, locale: locale)
                    var kinds: [HomeEntry.Kind] = []
                    for item in items where !kinds.contains(item.kind) { kinds.append(item.kind) }
                    return CalendarDayMarker(
                        date: day, kinds: Array(kinds.prefix(3)),
                        sampleImageName: items.flatMap(\.sampleImageNames).first)
                })
        }

        func periodicTodos(for date: Date, locale: Locale) async throws -> [CalendarTodoGroup] {
            try Task.checkCancellation()
            return CalendarTodoPeriod.notePeriods.compactMap { period in
                let items = periodEntries(period, containing: date, locale: locale)
                return items.isEmpty ? nil : CalendarTodoGroup(period: period, entries: items)
            }
        }

        func createEntry(_ draft: CalendarEntryDraft) async throws {
            try Task.checkCancellation()
            guard draft.isValid,
                HomeCalendar(calendar: calendar).supportedDates.contains(
                    calendar.startOfDay(for: draft.date))
            else { throw HomeDataError.invalidEntry }
            let id = UUID().uuidString
            if draft.kind == .todo, draft.period != nil {
                recurringDrafts.append((id, draft))
            } else {
                createdEntries.append(
                    HomeEntry(
                        id: id, kind: draft.kind.entryKind,
                        title: draft.title.trimmingCharacters(in: .whitespacesAndNewlines),
                        detail: draft.detail, date: draft.date))
            }
        }

        /// 日/周/月待办按自然周期生成实例 ID；周/月便签读取相同实例。
        private func periodEntries(
            _ period: CalendarTodoPeriod, containing day: Date, locale: Locale
        ) -> [HomeEntry] {
            let dates = HomeCalendar(calendar: calendar)
            let end = dates.periodEnd(period, containing: day)
            let key = Int(end.timeIntervalSince1970)
            let currentEnd = dates.periodEnd(period, containing: today)
            var result: [HomeEntry] = []
            if period != .daily, end >= currentEnd {
                let keys: [String.LocalizationValue] =
                    period == .weekly
                    ? [
                        "calendar.demo.week.1", "calendar.demo.week.2", "calendar.demo.week.3",
                        "calendar.demo.week.4",
                    ]
                    : [
                        "calendar.demo.month.1", "calendar.demo.month.2", "calendar.demo.month.3",
                        "calendar.demo.month.4", "calendar.demo.month.5", "calendar.demo.month.6",
                    ]
                result = keys.enumerated().map { index, title in
                    let id = "period.\(period.rawValue).\(key).\(index)"
                    return HomeEntry(
                        id: id, kind: .todo, title: AppLocalization.string(title, locale: locale),
                        detail: AppLocalization.string("calendar.demo.period.detail", locale: locale),
                        date: end,
                        isCompleted: completedOverrides[id]
                            ?? (end == currentEnd && index < (period == .weekly ? 2 : 3)))
                }
            }
            for value in recurringDrafts where value.draft.period == period {
                let occurrence = period == .weekly
                    ? dates.weeklyOccurrence(weekday: value.draft.recurrenceWeekday, containing: day)
                    : end
                guard occurrence >= calendar.startOfDay(for: value.draft.date) else { continue }
                let id = "\(value.id).\(key)"
                result.append(
                    HomeEntry(
                        id: id, kind: .todo,
                        title: value.draft.title.trimmingCharacters(in: .whitespacesAndNewlines),
                        detail: value.draft.detail, date: occurrence, isCompleted: completedOverrides[id] ?? false))
            }
            return result
        }

        private func entries(for day: Date, locale: Locale) -> [HomeEntry] {
            var additional = createdEntries.filter { calendar.isDate($0.date, inSameDayAs: day) }
            for period in CalendarTodoPeriod.allCases {
                additional += periodEntries(period, containing: day, locale: locale)
                    .filter { calendar.isDate($0.date, inSameDayAs: day) }
            }
            additional = additional.map { entry in
                var entry = entry
                entry.isCompleted = completedOverrides[entry.id] ?? entry.isCompleted
                return entry
            }
            guard day <= today else { return additional.sorted { $0.date < $1.date } }
            let seed = calendar.ordinality(of: .day, in: .era, for: day) ?? 1
            func text(_ key: String.LocalizationValue) -> String {
                AppLocalization.string(key, locale: locale)
            }
            func entry(
                _ suffix: String, _ kind: HomeEntry.Kind, _ title: String.LocalizationValue,
                _ detail: String.LocalizationValue, _ hour: Int, _ minute: Int = 0,
                amount: Int = 0, duration: Int = 0, completed: Bool = false
            ) -> HomeEntry {
                let id = "\(Int(day.timeIntervalSince1970)).\(suffix)"
                return HomeEntry(
                    id: id, kind: kind, title: text(title), detail: text(detail),
                    date: calendar.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day,
                    isCompleted: completedOverrides[id] ?? completed,
                    amountInCents: amount, durationMinutes: duration
                )
            }
            var sample = [
                entry("reading", .todo, "home.demo.read", "home.demo.todo_detail", 9, completed: true),
                entry(
                    "coffee", .expense, "home.demo.coffee", "home.demo.expense_detail", 9, 30,
                    amount: 1800 + seed % 4 * 300),
                entry(
                    "focus", .focus, "home.demo.focus", "home.demo.focus_detail", 10,
                    duration: 35 + seed % 5 * 15),
                entry("document", .document, "home.demo.document", "home.demo.document_detail", 11, 20),
                entry(
                    "lunch", .expense, "home.demo.lunch", "home.demo.expense_detail", 12, 10,
                    amount: 2800 + seed % 7 * 450),
                entry("plan", .todo, "home.demo.plan", "home.demo.todo_detail", 15),
                entry("walk", .todo, "home.demo.walk", "home.demo.todo_detail", 18, 30),
            ]
            if calendar.isDate(day, inSameDayAs: today) {
                sample.append(
                    entry("meeting", .schedule, "calendar.demo.schedule", "calendar.demo.room", 14, 30))
                sample.append(
                    entry(
                        "income", .income, "calendar.demo.income", "calendar.demo.income.detail", 18,
                        amount: 26000))
            }
            // 历史样例图片必须和当日记录来自同一数据源，月格不能自行伪造封面。
            let daysAgo = calendar.dateComponents([.day], from: day, to: today).day ?? 0
            if [0, 2, 5, 12].contains(daysAgo) {
                var photo = entry(
                    "photo", .photo, "calendar.demo.photo", "calendar.demo.photo.detail", 17, 10)
                photo.sampleImageNames = ["PolaroidLandscape"]
                sample.append(photo)
            }
            return (sample + additional).sorted { $0.date < $1.date }
        }
    }
#endif
