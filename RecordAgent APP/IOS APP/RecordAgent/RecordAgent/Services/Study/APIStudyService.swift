import Foundation

/// 与首页、日历共用 `agent-backend` 的 `/agent/study-sessions` 时段。
actor APIStudyService: StudyServiceProtocol {
    private let client: APIClient

    init(client: APIClient) { self.client = client }

    func entries(month: String, timeZone: String, query: String, offset: Int, limit: Int) async throws -> StudyPage {
        let (from, to) = try Self.monthBounds(month)
        let sessions: [BackendStudySessionDTO] = try await client.sendResult(
            "/agent/study-sessions",
            query: [
                URLQueryItem(name: "from", value: from),
                URLQueryItem(name: "to", value: to),
                URLQueryItem(name: "timezone", value: timeZone),
            ]
        )
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered = try sessions.compactMap { dto -> StudyEntry? in
            let entry = try dto.entry()
            if !trimmedQuery.isEmpty, !entry.title.lowercased().contains(trimmedQuery) {
                return nil
            }
            return entry
        }
        let start = min(max(0, offset), filtered.count)
        let end = min(start + max(1, limit), filtered.count)
        let slice = Array(filtered[start..<end])
        let hasMore = end < filtered.count
        return StudyPage(items: slice, nextOffset: hasMore ? end : nil, hasMore: hasMore)
    }

    func overview(month: String, timeZone: String) async throws -> StudyOverview {
        let (from, to) = try Self.monthBounds(month)
        let stats: BackendStudyStatisticsDTO = try await client.sendResult(
            "/agent/study-sessions/statistics",
            query: [
                URLQueryItem(name: "from", value: from),
                URLQueryItem(name: "to", value: to),
                URLQueryItem(name: "timezone", value: timeZone),
            ]
        )
        return StudyOverview(
            minutes: max(0, stats.totalDurationSeconds / 60),
            sessionCount: max(0, Int64(stats.sessionCount)),
            activeDays: max(0, Int64(stats.studyDays))
        )
    }

    func active() async throws -> StudyEntry? {
        let dto: BackendStudySessionDTO? = try await client.sendOptionalResult("/agent/study-sessions/active")
        guard let dto else { return nil }
        let entry = try dto.entry()
        guard entry.isActive else { throw APIClientError.invalidResponse }
        return entry
    }

    /// Spring 在无活跃时段时可返回空响应体或 JSON null，两者都代表真实空状态。
    static func decodeActive(_ data: Data) throws -> StudyEntry? {
        if data.isEmpty { return nil }
        let entry = try APIClient.decoder.decode(StudyEntryDTO?.self, from: data)?.entry()
        guard entry?.isActive ?? true else { throw APIClientError.invalidResponse }
        return entry
    }

    func create(_ draft: StudyDraft) async throws -> StudyEntry {
        if let endedAt = draft.endedAt {
            let body = try APIClient.encoder.encode(
                ManualStudyRequest(
                    content: draft.title,
                    category: nil,
                    startedAt: Self.instant(draft.startedAt),
                    endedAt: Self.instant(endedAt),
                    timezone: draft.timeZone,
                    location: nil
                )
            )
            let dto: BackendStudySessionDTO = try await client.sendResult(
                "/agent/study-sessions/manual", method: "POST", body: body)
            return try dto.entry()
        } else {
            let body = try APIClient.encoder.encode(
                StartStudyRequest(
                    content: draft.title,
                    category: nil,
                    timezone: draft.timeZone
                )
            )
            let dto: BackendStudySessionDTO = try await client.sendResult(
                "/agent/study-sessions/start", method: "POST", body: body)
            return try dto.entry()
        }
    }

    func finish(id: UUID, version: Int64, endedAt: Date) async throws -> StudyEntry {
        let dto: BackendStudySessionDTO = try await client.sendResult(
            "/agent/study-sessions/\(id.uuidString.lowercased())/finish",
            method: "POST",
            query: [URLQueryItem(name: "expectedVersion", value: String(version))]
        )
        return try dto.entry()
    }

    func delete(id: UUID, version: Int64) async throws {
        let _: String = try await client.sendResult(
            "/agent/study-sessions/\(id.uuidString.lowercased())",
            method: "DELETE",
            query: [URLQueryItem(name: "expectedVersion", value: String(version))]
        )
    }

    private static func instant(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    private static func monthBounds(_ month: String) throws -> (String, String) {
        let parts = month.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 2,
              let start = Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: 1)),
              let end = Calendar.current.date(byAdding: DateComponents(month: 1, day: -1), to: start)
        else { throw APIClientError.invalidResponse }
        let df = DateFormatter()
        df.calendar = Calendar(identifier: .gregorian)
        df.dateFormat = "yyyy-MM-dd"
        return (df.string(from: start), df.string(from: end))
    }
}

private struct BackendStudySessionDTO: Decodable {
    let id: String
    let content: String
    let category: String?
    let startedAt: Date
    let endedAt: Date?
    let timezone: String
    let version: Int

    func entry() throws -> StudyEntry {
        let uuid = UUID(uuidString: id) ?? UUID()
        guard version >= 0, TimeZone(identifier: timezone) != nil,
              endedAt.map({ $0 >= startedAt }) ?? true
        else { throw APIClientError.invalidResponse }
        return StudyEntry(
            id: uuid,
            title: content,
            startedAt: startedAt,
            endedAt: endedAt,
            timeZone: timezone,
            version: Int64(version)
        )
    }
}

private struct BackendStudyStatisticsDTO: Decodable {
    let totalDurationSeconds: Int64
    let studyDays: Int
    let sessionCount: Int
}

private struct StartStudyRequest: Encodable {
    let content: String
    let category: String?
    let timezone: String
}

private struct ManualStudyRequest: Encodable {
    let content: String
    let category: String?
    let startedAt: String
    let endedAt: String
    let timezone: String
    let location: String?
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
