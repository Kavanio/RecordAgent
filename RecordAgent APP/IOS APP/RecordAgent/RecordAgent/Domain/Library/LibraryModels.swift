import Foundation

enum LibraryDocumentKind: String, CaseIterable, Identifiable, Codable, Sendable {
    case quick, learning, reading, journal
    case weeklyReview = "weekly_review"
    var id: String { rawValue }
}

enum LibraryCollection: String, CaseIterable, Identifiable, Sendable {
    case all, favorites, trash
    var id: String { rawValue }
}

struct LibraryItem: Decodable, Identifiable, Equatable, Sendable {
    let id: String
    let date: String
    let type: LibraryDocumentKind
    let title: String
    let excerpt: String
    let characterCount: Int64
    let favorite: Bool
    let trashed: Bool
    let version: Int64
}

struct LibraryDocument: Decodable, Identifiable, Equatable, Sendable {
    let id: String
    let date: String
    let type: LibraryDocumentKind
    let title: String
    let body: String
    let favorite: Bool
    let trashed: Bool
    let version: Int64
}

/// UUID 在编辑器生命周期内固定；失败重试保留正文、版本和身份。
struct LibraryDraft: Encodable, Sendable, Equatable {
    let id: String
    var date: String
    var type: LibraryDocumentKind
    var title: String
    var body: String
    let timeZone: String
    var version: Int64

    init(id: String = UUID().uuidString, date: String, type: LibraryDocumentKind = .quick,
         title: String = "", body: String = "", version: Int64 = 0,
         timeZone: String = TimeZone.current.identifier) {
        self.id = id
        self.date = date
        self.type = type
        self.title = title
        self.body = body
        self.version = version
        self.timeZone = timeZone
    }

    init(document: LibraryDocument, timeZone: String = TimeZone.current.identifier) {
        self.init(id: document.id, date: document.date, type: document.type, title: document.title,
                  body: document.body, version: document.version, timeZone: timeZone)
    }

    var isValid: Bool {
        !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && title.utf16.count <= 240 && body.utf16.count <= 100_000
    }
}

struct LibraryPage: Decodable, Sendable {
    let items: [LibraryItem]
    let nextOffset: Int?
    let hasMore: Bool
}

protocol LibraryServiceProtocol: Sendable {
    func page(type: LibraryDocumentKind?, collection: LibraryCollection, query: String,
              offset: Int) async throws -> LibraryPage
    func document(id: String) async throws -> LibraryDocument
    func save(_ draft: LibraryDraft, creating: Bool) async throws -> LibraryDocument
    func flags(id: String, version: Int64, favorite: Bool, trashed: Bool) async throws -> LibraryDocument
}
