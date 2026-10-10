import Foundation

actor APIMobileTaskService: MobileTaskServiceProtocol {
    private let client: APIClient
    init(client: APIClient) { self.client = client }

    func list(query: String, enabled: Bool?, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTask> {
        var items = [URLQueryItem(name: "query", value: query), URLQueryItem(name: "offset", value: String(offset)),
                     URLQueryItem(name: "limit", value: String(limit))]
        if let enabled { items.append(URLQueryItem(name: "enabled", value: String(enabled))) }
        let data = try await client.send("/api/v1/tasks", query: items)
        return try APIClient.decoder.decode(MobileTaskPage<MobileTask>.self, from: data)
    }

    func task(id: String) async throws -> MobileTask {
        let data = try await client.send("/api/v1/tasks/\(id)")
        return try APIClient.decoder.decode(MobileTask.self, from: data)
    }

    func enabledTasks() async throws -> [MobileTask] {
        let data = try await client.send("/api/v1/tasks/enabled")
        return try APIClient.decoder.decode([MobileTask].self, from: data)
    }

    func save(_ task: MobileTask, creating: Bool) async throws -> MobileTask {
        let title = task.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.utf16.count <= 120, task.body.utf16.count <= 500 else { throw MobileTaskError.invalidTitle }
        _ = try MobileTaskSchedule.components(for: task)
        if task.enabled && MobileTaskSchedule.nextDate(for: task) == nil { throw MobileTaskError.invalidSchedule }
        let path = creating ? "/api/v1/tasks" : "/api/v1/tasks/\(task.id)"
        var normalized = task
        normalized.title = title
        let data = try await client.send(path, method: creating ? "POST" : "PUT", body: APIClient.encoder.encode(normalized))
        return try APIClient.decoder.decode(MobileTask.self, from: data)
    }

    func delete(id: String, version: Int64) async throws {
        _ = try await client.send("/api/v1/tasks/\(id)", method: "DELETE", query: [URLQueryItem(name: "version", value: String(version))])
    }

    func deliveries(taskID: String, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTaskDelivery> {
        let data = try await client.send("/api/v1/tasks/\(taskID)/deliveries", query: [
            URLQueryItem(name: "offset", value: String(offset)), URLQueryItem(name: "limit", value: String(limit))])
        return try APIClient.decoder.decode(MobileTaskPage<MobileTaskDelivery>.self, from: data)
    }

    func observe(_ observation: MobileTaskObservation) async throws {
        let encoder = APIClient.encoder
        encoder.dateEncodingStrategy = .iso8601
        let body = try encoder.encode(ObservationBody(eventId: observation.eventID, deliveredAt: observation.deliveredAt))
        _ = try await client.send("/api/v1/tasks/\(observation.taskID)/deliveries", method: "POST", body: body)
    }
}

private struct ObservationBody: Encodable {
    let eventId: String
    let deliveredAt: Date
}
