import Foundation

/// 资料库对接 `agent-backend` 的 `/agent/records`。
actor APILibraryService: LibraryServiceProtocol {
    private let client: APIClient
    init(client: APIClient) { self.client = client }

    func page(
        type: LibraryDocumentKind?, collection: LibraryCollection, query: String,
        offset: Int
    ) async throws -> LibraryPage {
        let records: [BackendRecordResponse]
        if collection == .trash {
            records = try await client.sendResult("/agent/records/trash")
        } else {
            let (from, to) = Self.safeTwoYearWindow()
            var items = [
                URLQueryItem(name: "from", value: from),
                URLQueryItem(name: "to", value: to),
            ]
            if let type {
                items.append(URLQueryItem(name: "type", value: type.rawValue))
            }
            let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                items.append(URLQueryItem(name: "query", value: trimmed))
            }
            records = try await client.sendResult("/agent/records", query: items)
        }

        let filtered = records.filter { record in
            if collection == .favorites { return record.favorite }
            return true
        }.map { $0.toItem() }

        let pageSize = 50
        let start = min(max(0, offset), filtered.count)
        let end = min(start + pageSize, filtered.count)
        let slice = Array(filtered[start..<end])
        let hasMore = end < filtered.count
        return LibraryPage(items: slice, nextOffset: hasMore ? end : nil, hasMore: hasMore)
    }

    func document(id: String) async throws -> LibraryDocument {
        let record: BackendRecordResponse = try await client.sendResult("/agent/records/\(id)")
        return record.toDocument()
    }

    func save(_ draft: LibraryDraft, creating: Bool) async throws -> LibraryDocument {
        let body = try APIClient.encoder.encode(
            SaveRecordRequest(
                recordDate: draft.date,
                type: draft.type.rawValue,
                title: draft.title,
                contentHtml: nil,
                contentText: draft.body,
                tags: [],
                tabId: nil
            )
        )
        if creating {
            let record: BackendRecordResponse = try await client.sendResult(
                "/agent/records", method: "POST", body: body)
            return record.toDocument()
        } else {
            let record: BackendRecordResponse = try await client.sendResult(
                "/agent/records/\(draft.id)",
                method: "PUT",
                query: [URLQueryItem(name: "expectedVersion", value: String(draft.version))],
                body: body
            )
            return record.toDocument()
        }
    }

    func flags(id: String, version: Int64, favorite: Bool, trashed: Bool) async throws -> LibraryDocument {
        if trashed {
            let _: String = try await client.sendResult(
                "/agent/records/\(id)",
                method: "DELETE",
                query: [URLQueryItem(name: "expectedVersion", value: String(version))]
            )
            return LibraryDocument(
                id: id,
                date: "",
                type: .quick,
                title: "",
                body: "",
                favorite: favorite,
                trashed: true,
                version: version + 1
            )
        }
        let existing = try? await document(id: id)
        var currentVersion = Int(version)
        if existing?.trashed == true {
            let restored: BackendRecordResponse = try await client.sendResult(
                "/agent/records/\(id)/restore",
                method: "POST",
                query: [URLQueryItem(name: "expectedVersion", value: String(currentVersion))]
            )
            currentVersion = restored.version
            if restored.favorite == favorite {
                return restored.toDocument()
            }
        }
        let body = try APIClient.encoder.encode(
            UpdateFlagsRequest(pinned: nil, favorite: favorite, archived: nil, expectedVersion: currentVersion)
        )
        let record: BackendRecordResponse = try await client.sendResult(
            "/agent/records/\(id)/flags", method: "PATCH", body: body)
        return record.toDocument()
    }

    private static func safeTwoYearWindow(now: Date = .now) -> (String, String) {
        let calendar = Calendar(identifier: .gregorian)
        let start = calendar.date(byAdding: .day, value: -710, to: now) ?? now
        let end = calendar.date(byAdding: .day, value: 10, to: now) ?? now
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.dateFormat = "yyyy-MM-dd"
        return (formatter.string(from: start), formatter.string(from: end))
    }
}

private struct BackendRecordResponse: Decodable {
    let id: String
    let recordDate: String
    let type: String
    let title: String
    let contentText: String?
    let favorite: Bool
    let trashedAt: Date?
    let version: Int
    let createdAt: Date?
    let updatedAt: Date?

    func toItem() -> LibraryItem {
        let text = contentText ?? ""
        let excerpt = String(text.prefix(120))
        return LibraryItem(
            id: id,
            date: recordDate,
            type: LibraryDocumentKind(rawValue: type) ?? .quick,
            title: title,
            excerpt: excerpt,
            characterCount: Int64(text.count),
            favorite: favorite,
            trashed: trashedAt != nil,
            version: Int64(version),
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }

    func toDocument() -> LibraryDocument {
        LibraryDocument(
            id: id,
            date: recordDate,
            type: LibraryDocumentKind(rawValue: type) ?? .quick,
            title: title,
            body: contentText ?? "",
            favorite: favorite,
            trashed: trashedAt != nil,
            version: Int64(version),
            createdAt: createdAt,
            updatedAt: updatedAt
        )
    }
}

private struct SaveRecordRequest: Encodable {
    let recordDate: String
    let type: String
    let title: String
    let contentHtml: String?
    let contentText: String
    let tags: [String]
    let tabId: String?
}

private struct UpdateFlagsRequest: Encodable {
    let pinned: Bool?
    let favorite: Bool?
    let archived: Bool?
    let expectedVersion: Int
}
