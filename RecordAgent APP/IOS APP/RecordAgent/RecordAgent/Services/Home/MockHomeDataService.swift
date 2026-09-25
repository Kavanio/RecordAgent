import Foundation

#if DEBUG
    /// 可控开发假服务：日期决定样例内容，待办修改在本次会话内按日期保存。
    actor MockHomeDataService: HomeDataServiceProtocol {
        private let calendar: Calendar
        private let today: Date
        private var completedOverrides: [String: Bool] = [:]

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
            return HomeDaySnapshot(date: day, entries: entries(for: day, locale: locale), trend: trend, isDemo: true)
        }

        func setTodoCompleted(id: String, completed: Bool, date: Date) async throws {
            try Task.checkCancellation()
            guard
                entries(for: calendar.startOfDay(for: date), locale: .current)
                    .contains(where: { $0.id == id && $0.kind == .todo })
            else { throw HomeDataError.missingTodo }
            completedOverrides[id] = completed
        }

        private func entries(for day: Date, locale: Locale) -> [HomeEntry] {
            guard day <= today else { return [] }
            let seed = calendar.ordinality(of: .day, in: .era, for: day) ?? 1
            func text(_ key: String.LocalizationValue) -> String { AppLocalization.string(key, locale: locale) }
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
            return [
                entry("reading", .todo, "home.demo.read", "home.demo.todo_detail", 9, completed: true),
                entry(
                    "coffee", .expense, "home.demo.coffee", "home.demo.expense_detail", 9, 30,
                    amount: 1800 + seed % 4 * 300),
                entry("focus", .focus, "home.demo.focus", "home.demo.focus_detail", 10, duration: 35 + seed % 5 * 15),
                entry("document", .document, "home.demo.document", "home.demo.document_detail", 11, 20),
                entry(
                    "lunch", .expense, "home.demo.lunch", "home.demo.expense_detail", 12, 10,
                    amount: 2800 + seed % 7 * 450),
                entry("plan", .todo, "home.demo.plan", "home.demo.todo_detail", 15),
                entry("walk", .todo, "home.demo.walk", "home.demo.todo_detail", 18, 30),
            ].sorted { $0.date < $1.date }
        }
    }
#endif
