import Foundation

@MainActor
final class ProfileActivityViewModel: ObservableObject {
    enum State: Equatable {
        case loading
        case ready(ProfileActivityMonth)
        case unavailable
    }

    @Published private(set) var state: State = .loading
    private let service: any ProfileActivityServiceProtocol
    private var revision = 0

    init(service: any ProfileActivityServiceProtocol) {
        self.service = service
    }

    func load(account: String?, month: Date, locale: Locale) async {
        revision += 1
        let current = revision
        guard let account else {
            state = .unavailable
            return
        }
        state = .loading
        do {
            let result = try await service.month(for: account, startingAt: month, locale: locale)
            guard !Task.isCancelled, current == revision else { return }
            state = .ready(result)
        } catch {
            guard current == revision else { return }
            if !(error is CancellationError) { state = .unavailable }
        }
    }
}
