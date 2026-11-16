import Foundation

@MainActor
final class ProfileAppearanceViewModel: ObservableObject {
    @Published private(set) var appearance: ProfileAppearance = .empty
    @Published private(set) var isLoading = false
    @Published private(set) var isSaving = false
    @Published var error: Error?
    @Published var saveError: Error?

    private let service: any ProfileAppearanceServiceProtocol
    private var revision = 0

    init(service: any ProfileAppearanceServiceProtocol) {
        self.service = service
    }

    func load(account: String?) async {
        revision += 1
        let current = revision
        appearance = .empty
        error = nil
        guard let account else {
            isLoading = false
            return
        }
        isLoading = true
        do {
            let loaded = try await service.load(account: account)
            guard !Task.isCancelled, current == revision else { return }
            appearance = loaded
            error = nil
        } catch {
            guard current == revision else { return }
            if !(error is CancellationError) { self.error = error }
        }
        if current == revision { isLoading = false }
    }

    func save(_ updated: ProfileAppearance, account: String?) async -> Bool {
        guard let account, !isSaving else { return false }
        revision += 1
        isLoading = false
        isSaving = true
        saveError = nil
        defer { isSaving = false }
        do {
            try await service.save(updated, account: account)
            appearance = updated
            error = nil
            saveError = nil
            return true
        } catch {
            if !(error is CancellationError) { saveError = error }
            return false
        }
    }
}
