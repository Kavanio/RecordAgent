import Foundation

/// 手机端本地通知提醒任务服务，对接 `agent-backend` 的 `/agent/mobile-tasks`。
actor APIMobileTaskService: MobileTaskServiceProtocol {
    private let client: APIClient
    init(client: APIClient) { self.client = client }

    func list(query: String, enabled: Bool?, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTask> {
        var items = [
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "offset", value: String(offset)),
            URLQueryItem(name: "limit", value: String(limit)),
        ]
        if let enabled { items.append(URLQueryItem(name: "enabled", value: String(enabled))) }
        return try await client.sendResult("/agent/mobile-tasks", query: items)
    }

    func task(id: String) async throws -> MobileTask {
        try await client.sendResult("/agent/mobile-tasks/\(id)")
    }

    func enabledTasks() async throws -> [MobileTask] {
        try await client.sendResult("/agent/mobile-tasks/enabled")
    }

    func save(_ task: MobileTask, creating: Bool) async throws -> MobileTask {
        let title = task.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.utf16.count <= 120, task.body.utf16.count <= 500 else {
            throw MobileTaskError.invalidTitle
        }
        _ = try MobileTaskSchedule.components(for: task)
        if task.enabled && MobileTaskSchedule.nextDate(for: task) == nil {
            throw MobileTaskError.invalidSchedule
        }
        let path = creating ? "/agent/mobile-tasks" : "/agent/mobile-tasks/\(task.id)"
        var normalized = task
        normalized.title = title
        return try await client.sendResult(
            path,
            method: creating ? "POST" : "PUT",
            body: APIClient.encoder.encode(normalized)
        )
    }

    func delete(id: String, version: Int64) async throws {
        let _: String = try await client.sendResult(
            "/agent/mobile-tasks/\(id)",
            method: "DELETE",
            query: [URLQueryItem(name: "version", value: String(version))]
        )
    }

    func deliveries(taskID: String, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTaskDelivery> {
        try await client.sendResult(
            "/agent/mobile-tasks/\(taskID)/deliveries",
            query: [
                URLQueryItem(name: "offset", value: String(offset)),
                URLQueryItem(name: "limit", value: String(limit)),
            ]
        )
    }

    func observe(_ observation: MobileTaskObservation) async throws {
        let encoder = APIClient.encoder
        encoder.dateEncodingStrategy = .iso8601
        let body = try encoder.encode(
            ObservationBody(eventId: observation.eventID, deliveredAt: observation.deliveredAt))
        let _: String = try await client.sendResult(
            "/agent/mobile-tasks/\(observation.taskID)/deliveries", method: "POST", body: body)
    }
}

private struct ObservationBody: Encodable {
    let eventId: String
    let deliveredAt: Date
}
