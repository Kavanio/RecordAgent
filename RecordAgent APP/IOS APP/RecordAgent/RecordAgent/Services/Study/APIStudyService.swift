import Foundation

/// 与首页、日历共用服务端 study 时段；不另建本地计时事实。
actor APIStudyService: StudyServiceProtocol {
    private let client: APIClient

    init(client: APIClient) { self.client = client }

    func entries(month: String, timeZone: String, query: String, offset: Int, limit: Int) async throws -> StudyPage {
        let data = try await client.send("/api/v1/study", query: [
            URLQueryItem(name: "month", value: month),
            URLQueryItem(name: "timeZone", value: timeZone),
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "offset", value: String(offset)),
            URLQueryItem(name: "limit", value: String(limit))
        ])
        let page = try APIClient.decoder.decode(StudyPageDTO.self, from: data)
        guard !page.hasMore || page.nextOffset.map({ $0 > offset }) == true else {
            throw APIClientError.invalidResponse
        }
        return StudyPage(items: try page.items.map { try $0.entry() }, nextOffset: page.nextOffset, hasMore: page.hasMore)
    }

    func overview(month: String, timeZone: String) async throws -> StudyOverview {
        let data = try await client.send("/api/v1/study/overview", query: [
            URLQueryItem(name: "month", value: month), URLQueryItem(name: "timeZone", value: timeZone)
        ])
        let overview = try APIClient.decoder.decode(StudyOverviewDTO.self, from: data)
        guard overview.minutes >= 0, overview.sessionCount >= 0, overview.activeDays >= 0 else {
            throw APIClientError.invalidResponse
        }
        return StudyOverview(minutes: overview.minutes, sessionCount: overview.sessionCount, activeDays: overview.activeDays)
    }

    func active() async throws -> StudyEntry? {
        let data = try await client.send("/api/v1/study/active")
        return try Self.decodeActive(data)
    }

    /// Spring 在无活跃时段时可返回空响应体或 JSON null，两者都代表真实空状态。
    static func decodeActive(_ data: Data) throws -> StudyEntry? {
        if data.isEmpty { return nil }
        let entry = try APIClient.decoder.decode(StudyEntryDTO?.self, from: data)?.entry()
        guard entry?.isActive ?? true else { throw APIClientError.invalidResponse }
        return entry
    }

    func create(_ draft: StudyDraft) async throws -> StudyEntry {
        let body = try APIClient.encoder.encode(StudySaveDTO(
            id: draft.id, title: draft.title, startedAt: Self.instant(draft.startedAt),
            endedAt: draft.endedAt.map(Self.instant), timeZone: draft.timeZone))
        let data = try await client.send("/api/v1/study", method: "POST", body: body)
        return try APIClient.decoder.decode(StudyEntryDTO.self, from: data).entry()
    }

    func finish(id: UUID, version: Int64, endedAt: Date) async throws -> StudyEntry {
        let body = try APIClient.encoder.encode(StudyFinishDTO(version: version, endedAt: Self.instant(endedAt)))
        let data = try await client.send("/api/v1/study/\(id.uuidString)/finish", method: "PATCH", body: body)
        return try APIClient.decoder.decode(StudyEntryDTO.self, from: data).entry()
    }

    func delete(id: UUID, version: Int64) async throws {
        _ = try await client.send("/api/v1/study/\(id.uuidString)", method: "DELETE", query: [
            URLQueryItem(name: "version", value: String(version))
        ])
    }

    private static func instant(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }
}

private struct StudyEntryDTO: Decodable {
    let id: UUID
    let title: String
    let startedAt: Date
    let endedAt: Date?
    let timeZone: String
    let version: Int64

    func entry() throws -> StudyEntry {
        guard version >= 0, TimeZone(identifier: timeZone) != nil,
              endedAt.map({ $0 >= startedAt }) ?? true else { throw APIClientError.invalidResponse }
        return StudyEntry(id: id, title: title, startedAt: startedAt, endedAt: endedAt, timeZone: timeZone, version: version)
    }
}

private struct StudyPageDTO: Decodable {
    let items: [StudyEntryDTO]
    let nextOffset: Int?
    let hasMore: Bool
}

private struct StudyOverviewDTO: Decodable {
    let minutes: Int64
    let sessionCount: Int64
    let activeDays: Int64
}

private struct StudySaveDTO: Encodable {
    let id: UUID
    let title: String
    let startedAt: String
    let endedAt: String?
    let timeZone: String
}

private struct StudyFinishDTO: Encodable {
    let version: Int64
    let endedAt: String
}
