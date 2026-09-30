import Foundation
import Combine

@MainActor
final class LibraryWorkspaceViewModel: ObservableObject {
    @Published var collection: LibraryCollection = .all
    @Published var type: LibraryDocumentKind?
    @Published var query = ""
    @Published private(set) var items: [LibraryItem] = []
    @Published private(set) var isLoading = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var nextOffset: Int?
    let initialDate: Date
    let service: any LibraryServiceProtocol
    private var generation = 0
    private var loadedFilter: String?

    init(date: Date, service: any LibraryServiceProtocol) {
        initialDate = date
        self.service = service
    }

    var filterKey: String { "\(collection.rawValue)|\(type?.rawValue ?? "")|\(query)" }

    func loadIfNeeded() async {
        if loadedFilter != filterKey || errorMessage != nil { await load(reset: true) }
    }

    func load(reset: Bool) async {
        if !reset && (isLoading || nextOffset == nil) { return }
        if reset { generation += 1; items = []; nextOffset = nil; loadedFilter = nil }
        let request = generation
        let requestedFilter = filterKey
        let offset = reset ? 0 : (nextOffset ?? 0)
        isLoading = true
        errorMessage = nil
        defer { if request == generation { isLoading = false } }
        do {
            let page = try await service.page(type: type, collection: collection,
                query: String(query.prefix(200)), offset: offset)
            guard request == generation, requestedFilter == filterKey else { return }
            let existing = Set(items.map(\.id))
            items.append(contentsOf: page.items.filter { !existing.contains($0.id) })
            nextOffset = page.hasMore ? page.nextOffset : nil
            loadedFilter = requestedFilter
        } catch is CancellationError { }
        catch { if request == generation && requestedFilter == filterKey { errorMessage = error.localizedDescription } }
    }
}

@MainActor
final class LibraryDetailViewModel: ObservableObject {
    @Published private(set) var document: LibraryDocument?
    @Published private(set) var errorMessage: String?
    @Published private(set) var isLoading = false
    @Published private(set) var isUpdating = false
    let id: String
    let service: any LibraryServiceProtocol
    private var generation = 0

    init(id: String, service: any LibraryServiceProtocol) { self.id = id; self.service = service }

    func load() async {
        guard !isUpdating else { return }
        generation += 1
        let request = generation
        isLoading = true
        errorMessage = nil
        defer { if request == generation { isLoading = false } }
        do {
            let result = try await service.document(id: id)
            guard request == generation else { return }
            document = result
        } catch is CancellationError { }
        catch { if request == generation { errorMessage = error.localizedDescription } }
    }

    func setFlags(favorite: Bool, trashed: Bool) async -> Bool {
        guard let document, !isUpdating, !isLoading else { return false }
        isUpdating = true
        errorMessage = nil
        defer { isUpdating = false }
        do {
            self.document = try await service.flags(id: id, version: document.version,
                                                   favorite: favorite, trashed: trashed)
            return true
        } catch is CancellationError { return false }
        catch { errorMessage = error.localizedDescription; return false }
    }
}

@MainActor
final class LibraryEditorViewModel: ObservableObject {
    @Published var draft: LibraryDraft
    @Published private(set) var hasConflict = false
    @Published private(set) var latest: LibraryDocument?
    @Published private(set) var comparisonError: String?
    @Published private(set) var isLoadingLatest = false
    private(set) var creating: Bool
    private let service: any LibraryServiceProtocol

    init(draft: LibraryDraft, creating: Bool, service: any LibraryServiceProtocol) {
        self.draft = draft
        self.creating = creating
        self.service = service
    }

    func save() async throws -> LibraryDocument {
        do { return try await service.save(draft, creating: creating) }
        catch APIClientError.conflict { hasConflict = true; throw APIClientError.conflict }
    }

    /// 冲突时只读取用于比较，不改写用户草稿或自动提升写入版本。
    func loadLatest() async {
        guard !isLoadingLatest else { return }
        isLoadingLatest = true
        comparisonError = nil
        defer { isLoadingLatest = false }
        do { latest = try await service.document(id: draft.id) }
        catch is CancellationError { }
        catch { comparisonError = error.localizedDescription }
    }

    func adoptLatest() {
        guard let latest else { return }
        draft = LibraryDraft(document: latest)
        creating = false
        hasConflict = false
        self.latest = nil
    }

    func keepDraftAsNew() {
        draft = LibraryDraft(date: draft.date, type: draft.type, title: draft.title,
                             body: draft.body, timeZone: draft.timeZone)
        creating = true
        hasConflict = false
        latest = nil
    }
}
