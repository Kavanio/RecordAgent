import Foundation

/// 将后端待办事实映射为首页和日历共用的领域数据；未实现的业务不会显示示例内容。
actor APICalendarDataService: CalendarDataServiceProtocol {
    private let client: APIClient
    private var versions: [String: Int64] = [:]

    init(client: APIClient) { self.client = client }

    func snapshot(for date: Date, locale: Locale) async throws -> HomeDaySnapshot {
        let day = try await fetchDay(date)
        return HomeDaySnapshot(
            date: Calendar.current.startOfDay(for: date), entries: try entries(in: day), trend: [],
            isDemo: false)
    }

    func setTodoCompleted(id: String, completed: Bool, date: Date) async throws {
        let day = Self.dayString(date)
        if versions[id] == nil { _ = try await fetchDay(date) }
        guard let expectedVersion = versions[id] else { throw HomeDataError.missingTodo }
        let body = try APIClient.encoder.encode(
            CompletionRequest(completed: completed, expectedVersion: expectedVersion))
        do {
            let data = try await client.send(
                "/api/v1/calendar/todos/\(id)/occurrences/\(day)", method: "PATCH", body: body)
            let result = try APIClient.decoder.decode(CompletionResponse.self, from: data)
            versions[id] = result.version
        } catch APIClientError.conflict {
            versions.removeValue(forKey: id)
            throw APIClientError.conflict
        }
    }

    func month(for date: Date, locale: Locale) async throws -> CalendarMonthSnapshot {
        let month = Self.monthString(date)
        let data = try await client.send(
            "/api/v1/calendar/months/\(month)",
            query: [URLQueryItem(name: "timeZone", value: TimeZone.current.identifier)])
        let response = try APIClient.decoder.decode(MonthResponse.self, from: data)
        let markers = try response.days.map { item -> CalendarDayMarker in
            guard let date = Self.date(item.date) else { throw APIClientError.invalidResponse }
            return CalendarDayMarker(date: date, kinds: item.count > 0 ? [.todo] : [], sampleImageName: nil)
        }
        return CalendarMonthSnapshot(
            month: HomeCalendar().startOfMonth(containing: date), days: markers)
    }

    /// 周、月便签使用对应周期末的真实待办实例，不另建本机副本。
    func periodicTodos(for date: Date, locale: Locale) async throws -> [CalendarTodoGroup] {
        let calendar = HomeCalendar()
        var result: [CalendarTodoGroup] = []
        for period in CalendarTodoPeriod.allCases {
            let end = calendar.periodEnd(period, containing: date)
            let day = try await fetchDay(end)
            let matching = day.todos.filter { $0.recurrence == period.rawValue }
            if !matching.isEmpty {
                result.append(CalendarTodoGroup(period: period, entries: try matching.map(Self.entry)))
            }
        }
        return result
    }

    func createEntry(_ draft: CalendarEntryDraft) async throws {
        guard draft.isValid else { throw HomeDataError.invalidEntry }
        guard draft.kind == .todo else { throw APIClientError.unavailable }
        let date = Self.dayString(draft.date)
        let time = Calendar.current.dateComponents([.hour, .minute], from: draft.date)
        let formattedTime = String(format: "%02d:%02d:00", time.hour ?? 0, time.minute ?? 0)
        let body = try APIClient.encoder.encode(
            CreateTodoRequest(
                title: draft.title.trimmingCharacters(in: .whitespacesAndNewlines),
                detail: draft.detail, date: date, time: formattedTime,
                timeZone: TimeZone.current.identifier,
                recurrence: draft.period?.rawValue ?? "none"))
        _ = try await client.send("/api/v1/calendar/todos", method: "POST", body: body)
    }

    private func fetchDay(_ date: Date) async throws -> DayResponse {
        let data = try await client.send(
            "/api/v1/calendar/days/\(Self.dayString(date))",
            query: [URLQueryItem(name: "timeZone", value: TimeZone.current.identifier)])
        let response = try APIClient.decoder.decode(DayResponse.self, from: data)
        for todo in response.todos { versions[todo.id] = todo.version }
        return response
    }

    private func entries(in day: DayResponse) throws -> [HomeEntry] {
        try day.todos.map(Self.entry)
    }

    private static func entry(_ todo: TodoResponse) throws -> HomeEntry {
        guard let date = date(todo.date) else { throw APIClientError.invalidResponse }
        var components = Calendar.current.dateComponents([.year, .month, .day], from: date)
        if let time = todo.time?.split(separator: ":"), time.count >= 2 {
            components.hour = Int(time[0])
            components.minute = Int(time[1])
        }
        let instant = Calendar.current.date(from: components) ?? date
        return HomeEntry(
            id: todo.id, kind: .todo, title: todo.title, detail: todo.detail ?? "",
            date: instant, isCompleted: todo.completed)
    }

    private static func dayString(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    private static func monthString(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month], from: date)
        return String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
    }

    private static func date(_ value: String) -> Date? {
        let parts = value.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }
}

private struct DayResponse: Decodable {
    let date: String
    let todos: [TodoResponse]
}
private struct TodoResponse: Decodable {
    let id: String
    let title: String
    let detail: String?
    let date: String
    let time: String?
    let recurrence: String
    let completed: Bool
    let version: Int64
}
private struct MonthResponse: Decodable {
    let month: String
    let days: [MonthDayResponse]
}
private struct MonthDayResponse: Decodable {
    let date: String
    let count: Int
}
private struct CreateTodoRequest: Encodable {
    let title: String
    let detail: String
    let date: String
    let time: String
    let timeZone: String
    let recurrence: String
}
private struct CompletionRequest: Encodable {
    let completed: Bool
    let expectedVersion: Int64
}
private struct CompletionResponse: Decodable {
    let version: Int64
}
