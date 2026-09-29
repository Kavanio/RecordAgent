import Foundation

/// 为当前账户与自然月加载总览；旧请求不能覆盖新账户或新月份的结果。
@MainActor
final class ProfileOverviewViewModel: ObservableObject {
    enum State: Equatable {
        case loading
        case ready(ProfileOverview)
        case unavailable
    }

    @Published private(set) var state: State = .loading
    private let service: any ProfileOverviewServiceProtocol
    private var revision = 0

    init(service: any ProfileOverviewServiceProtocol) {
        self.service = service
    }

    func load(userID: String?, month: Date) async {
        revision += 1
        let currentRevision = revision
        guard let userID else {
            state = .unavailable
            return
        }
        state = .loading
        do {
            let overview = try await service.overview(for: userID, month: month)
            guard !Task.isCancelled, currentRevision == revision else { return }
            state = .ready(overview)
        } catch {
            guard currentRevision == revision else { return }
            if !(error is CancellationError) { state = .unavailable }
        }
    }
}
