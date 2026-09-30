import Foundation

actor APILibraryService: LibraryServiceProtocol {
    private let client: APIClient
    init(client: APIClient) { self.client = client }

    func page(type: LibraryDocumentKind?, collection: LibraryCollection, query: String,
              offset: Int) async throws -> LibraryPage {
        let data = try await client.send("/api/v1/library", query: [
            URLQueryItem(name: "type", value: type?.rawValue ?? ""),
            URLQueryItem(name: "favorite", value: String(collection == .favorites)),
            URLQueryItem(name: "trash", value: String(collection == .trash)),
            URLQueryItem(name: "query", value: query.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "offset", value: String(offset)),
            URLQueryItem(name: "limit", value: "50")
        ])
        return try APIClient.decoder.decode(LibraryPage.self, from: data)
    }

    func document(id: String) async throws -> LibraryDocument {
        let data = try await client.send("/api/v1/library/\(id)")
        return try APIClient.decoder.decode(LibraryDocument.self, from: data)
    }

    func save(_ draft: LibraryDraft, creating: Bool) async throws -> LibraryDocument {
        let path = creating ? "/api/v1/library" : "/api/v1/library/\(draft.id)"
        let data = try await client.send(path, method: creating ? "POST" : "PUT",
                                         body: APIClient.encoder.encode(draft))
        return try APIClient.decoder.decode(LibraryDocument.self, from: data)
    }

    func flags(id: String, version: Int64, favorite: Bool, trashed: Bool) async throws -> LibraryDocument {
        let body = try APIClient.encoder.encode(Flags(version: version, favorite: favorite, trashed: trashed))
        let data = try await client.send("/api/v1/library/\(id)/flags", method: "PATCH", body: body)
        return try APIClient.decoder.decode(LibraryDocument.self, from: data)
    }

    private struct Flags: Encodable {
        let version: Int64
        let favorite: Bool
        let trashed: Bool
    }
}
