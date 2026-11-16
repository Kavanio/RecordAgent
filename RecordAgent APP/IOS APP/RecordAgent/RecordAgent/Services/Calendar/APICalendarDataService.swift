import Foundation

/// 将 `agent-backend` 的 `/agent/daily-events` 响应映射为首页和日历共用的领域数据。
actor APICalendarDataService: CalendarDataServiceProtocol {
    private let client: APIClient
    private var versions: [String: Int64] = [:]

    init(client: APIClient) { self.client = client }

    func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot {
        try await snapshot(for: date, locale: locale, includeTrend: true)
    }

    func snapshot(for date: Date, locale: Locale, includeTrend: Bool) async throws -> HomeDaySnapshot {
        let day = try await fetchDay(date)
        let trend = includeTrend ? ((try? await fetchTrend(date)) ?? []) : []
        return HomeDaySnapshot(
            date: Calendar.current.startOfDay(for: date),
            entries: try entries(in: day),
            trend: trend,
            isDemo: false
        )
    }

    func setTodoCompleted(id: String, completed: Bool, date: Date) async throws {
        let day = Self.dayString(date)
        if versions[id] == nil { _ = try await fetchDay(date) }
        guard let expectedVersion = versions[id] else { throw HomeDataError.missingTodo }
        let body = try APIClient.encoder.encode(
            TodoCompletionRequest(
                completed: completed,
                expectedVersion: Int(expectedVersion),
                occurrenceDate: day
            )
        )
        do {
            let updated: BackendEventResponse = try await client.sendResult(
                "/agent/daily-events/\(id)/todo-completion",
                method: "PATCH",
                body: body
            )
            versions[id] = Int64(updated.version)
        } catch APIClientError.conflict {
            versions.removeValue(forKey: id)
            throw APIClientError.conflict
        }
    }

    func month(for date: Date, locale: Locale) async throws -> CalendarMonthSnapshot {
        let calendar = HomeCalendar()
        let monthStart = calendar.startOfMonth(containing: date)
        guard let monthEnd = Calendar.current.date(byAdding: DateComponents(month: 1, day: -1), to: monthStart)
        else { throw APIClientError.invalidResponse }

        let summaries: [BackendDaySummaryResponse] = try await client.sendResult(
            "/agent/daily-events/days",
            query: [
                URLQueryItem(name: "from", value: Self.dayString(monthStart)),
                URLQueryItem(name: "to", value: Self.dayString(monthEnd)),
            ]
        )

        let markers = try summaries.map { item -> CalendarDayMarker in
            guard let itemDate = Self.date(item.date) else { throw APIClientError.invalidResponse }
            var kinds: [HomeEntry.Kind] = []
            for entry in item.items ?? [] {
                if let kind = Self.mapKind(entry.type), !kinds.contains(kind) {
                    kinds.append(kind)
                }
            }
            return CalendarDayMarker(date: itemDate, kinds: kinds, sampleImageName: nil)
        }
        return CalendarMonthSnapshot(month: monthStart, days: markers)
    }

    /// 周便签读取整周各星期的真实实例，月便签读取月末；共用完成状态与版本。
    func periodicTodos(for date: Date, locale: Locale) async throws -> [CalendarTodoGroup] {
        let todos: [BackendRecurringTodoResponse] = try await client.sendResult(
            "/agent/daily-events/recurring-todos",
            query: [URLQueryItem(name: "date", value: Self.dayString(date))]
        )

        var weeklyEntries: [HomeEntry] = []
        var monthlyEntries: [HomeEntry] = []

        for todo in todos {
            versions[todo.id] = Int64(todo.version)
            guard let occDate = Self.date(todo.occurrenceDate) else { continue }
            let entry = HomeEntry(
                id: todo.id,
                kind: .todo,
                title: todo.title,
                detail: "",
                date: occDate,
                isCompleted: todo.completed
            )
            switch todo.recurrence.uppercased() {
            case "WEEKLY": weeklyEntries.append(entry)
            case "MONTHLY": monthlyEntries.append(entry)
            default: break
            }
        }

        var result: [CalendarTodoGroup] = []
        if !weeklyEntries.isEmpty {
            result.append(CalendarTodoGroup(period: .weekly, entries: weeklyEntries))
        }
        if !monthlyEntries.isEmpty {
            result.append(CalendarTodoGroup(period: .monthly, entries: monthlyEntries))
        }
        return result
    }

    func createEntry(_ draft: CalendarEntryDraft) async throws {
        guard draft.isValid else { throw HomeDataError.invalidEntry }
        let date = Self.dayString(draft.date)
        let time = Calendar.current.dateComponents([.hour, .minute], from: draft.date)
        let formattedTime = String(format: "%02d:%02d", time.hour ?? 0, time.minute ?? 0)
        let trimmedTitle = draft.title.trimmingCharacters(in: .whitespacesAndNewlines)

        switch draft.kind {
        case .todo:
            let recurrence = (draft.period?.rawValue ?? "none").uppercased()
            let body = try APIClient.encoder.encode(
                CreateTodoRequest(
                    eventDate: date,
                    title: trimmedTitle,
                    time: draft.hasSpecificTime ? formattedTime : nil,
                    priority: "MEDIUM",
                    recurrence: recurrence,
                    recurrenceWeekday: draft.period == .weekly ? draft.recurrenceWeekday : nil,
                    recurrenceMonthDay: nil
                )
            )
            let _: BackendEventResponse = try await client.sendResult(
                "/agent/daily-events/todos", method: "POST", body: body)
        case .schedule:
            let body = try APIClient.encoder.encode(
                CreateScheduleRequest(
                    eventDate: date,
                    title: trimmedTitle,
                    startTime: formattedTime,
                    endTime: nil,
                    location: draft.detail.isEmpty ? nil : draft.detail,
                    timezone: TimeZone.current.identifier
                )
            )
            let _: BackendEventResponse = try await client.sendResult(
                "/agent/daily-events/schedules", method: "POST", body: body)
        case .note:
            let body = try APIClient.encoder.encode(
                CreateJournalRequest(
                    eventDate: date,
                    title: trimmedTitle,
                    body: draft.detail,
                    mood: nil
                )
            )
            let _: BackendEventResponse = try await client.sendResult(
                "/agent/daily-events/journals", method: "POST", body: body)
        }
    }

    private func fetchDay(_ date: Date) async throws -> BackendDayResponse {
        let day: BackendDayResponse = try await client.sendResult(
            "/agent/daily-events/days/\(Self.dayString(date))"
        )
        for event in day.events where event.eventType.uppercased() == "TODO" {
            versions[event.id] = Int64(event.version)
        }
        return day
    }

    private func fetchTrend(_ date: Date) async throws -> [HomeTrendPoint] {
        let end = Calendar.current.startOfDay(for: date)
        guard let start = Calendar.current.date(byAdding: .day, value: -6, to: end) else {
            return []
        }
        let fromStr = Self.dayString(start)
        let toStr = Self.dayString(end)
        let summaries: [BackendDaySummaryResponse] = try await client.sendResult(
            "/agent/daily-events/days",
            query: [
                URLQueryItem(name: "from", value: fromStr),
                URLQueryItem(name: "to", value: toStr),
            ]
        )
        let studyStats: BackendStudyTrendResponse? = try? await client.sendResult(
            "/agent/study-sessions/statistics",
            query: [
                URLQueryItem(name: "from", value: fromStr),
                URLQueryItem(name: "to", value: toStr),
                URLQueryItem(name: "timezone", value: TimeZone.current.identifier),
            ]
        )
        var focusByDay: [String: Int] = [:]
        for item in studyStats?.days ?? [] {
            focusByDay[item.date] = Int(max(0, item.durationSeconds / 60))
        }
        return try summaries.map { summary in
            guard let itemDate = Self.date(summary.date) else {
                throw APIClientError.invalidResponse
            }
            let cents = Int(((summary.expenseTotal ?? 0) * 100).rounded())
            return HomeTrendPoint(
                date: itemDate,
                expenseInCents: cents,
                focusMinutes: focusByDay[summary.date] ?? 0
            )
        }
    }

    private func entries(in day: BackendDayResponse) throws -> [HomeEntry] {
        try day.events.compactMap(Self.entry).sorted { $0.date < $1.date }
    }

    private static func entry(_ event: BackendEventResponse) throws -> HomeEntry? {
        guard let date = date(event.eventDate) else { throw APIClientError.invalidResponse }
        let type = event.eventType.uppercased()
        let details = event.details

        switch type {
        case "TODO":
            let instant = combine(date: date, time: details?.time)
            return HomeEntry(
                id: event.id,
                kind: .todo,
                title: event.title,
                detail: "",
                date: instant,
                isCompleted: details?.completed ?? false
            )
        case "SCHEDULE":
            let instant = combine(date: date, time: details?.startTime)
            return HomeEntry(
                id: event.id,
                kind: .schedule,
                title: event.title,
                detail: details?.location ?? "",
                date: instant
            )
        case "JOURNAL", "RECORD":
            return HomeEntry(
                id: event.id,
                kind: .document,
                title: event.title,
                detail: details?.body ?? "",
                date: journalTimelineDate(recordDate: date, createdAt: event.createdAt),
                createdAt: event.createdAt,
                updatedAt: event.updatedAt
            )
        case "EXPENSE", "INCOME":
            let kind: HomeEntry.Kind = type == "INCOME" ? .income : .expense
            let fallback = type == "INCOME"
                ? String(localized: "calendar.finance.income")
                : String(localized: "calendar.finance.expense")
            let instant = combine(date: date, time: details?.time)
            let cents = Int(((details?.amount ?? 0) * 100).rounded())
            let title = details?.category.flatMap { $0.isEmpty ? nil : $0 } ?? fallback
            return HomeEntry(
                id: event.id,
                kind: kind,
                title: title,
                detail: details?.note ?? "",
                date: instant,
                amountInCents: cents
            )
        case "STUDY", "FOCUS":
            let startInstant = details?.startedAt.flatMap(parseISO) ?? date
            let endInstant = details?.endedAt.flatMap(parseISO)
            let minutes: Int?
            if let endInstant {
                minutes = max(1, Int(endInstant.timeIntervalSince(startInstant) / 60))
            } else {
                minutes = nil
            }
            return HomeEntry(
                id: event.id,
                kind: .focus,
                title: details?.category.flatMap { $0.isEmpty ? nil : $0 } ?? event.title,
                detail: "",
                date: startInstant,
                durationMinutes: minutes ?? 0
            )
        default:
            return nil
        }
    }

    private static func mapKind(_ raw: String) -> HomeEntry.Kind? {
        switch raw.uppercased() {
        case "TODO": return .todo
        case "SCHEDULE": return .schedule
        case "JOURNAL", "RECORD": return .document
        case "EXPENSE": return .expense
        case "INCOME": return .income
        case "STUDY", "FOCUS": return .focus
        default: return nil
        }
    }

    private static func combine(date: Date, time: String?) -> Date {
        var components = Calendar.current.dateComponents([.year, .month, .day], from: date)
        if let parts = time?.split(separator: ":"), parts.count >= 2 {
            components.hour = Int(parts[0])
            components.minute = Int(parts[1])
        }
        return Calendar.current.date(from: components) ?? date
    }

    private static func parseISO(_ raw: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let parsed = fractional.date(from: raw) { return parsed }
        return ISO8601DateFormatter().date(from: raw)
    }

    /// 归属日期仍按用户选择，时间和排序取首次保存的创建时刻，编辑不会移动节点。
    static func journalTimelineDate(recordDate: Date, createdAt: Date,
                                    calendar: Calendar = .current) -> Date {
        let time = calendar.dateComponents([.hour, .minute, .second], from: createdAt)
        return calendar.date(bySettingHour: time.hour ?? 0, minute: time.minute ?? 0,
                             second: time.second ?? 0, of: recordDate) ?? recordDate
    }

    private static func dayString(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    private static func date(_ value: String) -> Date? {
        let parts = value.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}

private struct BackendDayResponse: Decodable {
    let date: String
    let events: [BackendEventResponse]
}

private struct BackendEventResponse: Decodable {
    let id: String
    let eventDate: String
    let eventType: String
    let title: String
    let source: String?
    let status: String?
    let version: Int
    let createdAt: Date
    let updatedAt: Date
    let details: BackendEventDetails?
}

private struct BackendEventDetails: Decodable {
    let time: String?
    let priority: String?
    let completed: Bool?
    let recurrence: String?
    let recurrenceWeekday: Int?
    let recurrenceMonthDay: Int?
    let startTime: String?
    let endTime: String?
    let location: String?
    let timezone: String?
    let category: String?
    let note: String?
    let amount: Double?
    let currency: String?
    let body: String?
    let mood: String?
    let startedAt: String?
    let endedAt: String?
}

private struct BackendDaySummaryResponse: Decodable {
    let date: String
    let eventCount: Int?
    let todoCount: Int?
    let completedTodoCount: Int?
    let scheduleCount: Int?
    let expenseTotal: Double?
    let headline: String?
    let items: [BackendCalendarItemResponse]?
}

private struct BackendStudyTrendResponse: Decodable {
    let days: [BackendStudyDayStat]
}

private struct BackendStudyDayStat: Decodable {
    let date: String
    let durationSeconds: Int64
}

private struct BackendCalendarItemResponse: Decodable {
    let id: String
    let type: String
    let title: String
}

private struct BackendRecurringTodoResponse: Decodable {
    let id: String
    let title: String
    let version: Int
    let recurrence: String
    let occurrenceDate: String
    let completed: Bool
}

private struct CreateTodoRequest: Encodable {
    let eventDate: String
    let title: String
    let time: String?
    let priority: String
    let recurrence: String
    let recurrenceWeekday: Int?
    let recurrenceMonthDay: Int?
}

private struct CreateScheduleRequest: Encodable {
    let eventDate: String
    let title: String
    let startTime: String
    let endTime: String?
    let location: String?
    let timezone: String
}

private struct CreateJournalRequest: Encodable {
    let eventDate: String
    let title: String
    let body: String
    let mood: String?
}

private struct TodoCompletionRequest: Encodable {
    let completed: Bool
    let expectedVersion: Int
    let occurrenceDate: String
}
