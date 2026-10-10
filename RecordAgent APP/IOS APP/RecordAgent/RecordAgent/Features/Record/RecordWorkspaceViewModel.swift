import Combine
import Foundation

/// MainTab 持有该实例；切换页面不会丢失筛选或未确认写入的重试信息。
@MainActor
final class RecordWorkspaceViewModel: ObservableObject {
    @Published var month: Date {
        didSet {
            if StudyRules.monthKey(month, calendar: calendar) != StudyRules.monthKey(oldValue, calendar: calendar) {
                invalidateHistory()
                overviewRevision += 1
                overview = nil
                overviewErrorKey = nil
            }
        }
    }
    @Published var query = "" {
        didSet { if query != oldValue { invalidateHistory() } }
    }
    @Published private(set) var entries: [StudyEntry] = []
    @Published private(set) var overview: StudyOverview?
    @Published private(set) var active: StudyEntry?
    @Published private(set) var isLoading = true
    @Published private(set) var isLoadingMore = false
    @Published private(set) var isLoadingOverview = false
    @Published private(set) var isRestoringActive = true
    @Published private(set) var isSaving = false
    @Published private(set) var hasMore = false
    @Published private(set) var historyErrorKey: String?
    @Published private(set) var overviewErrorKey: String?
    @Published private(set) var activeErrorKey: String?
    @Published var writeErrorKey: String?

    let calendar: Calendar
    private let service: any StudyServiceProtocol
    private var historyRevision = 0
    private var overviewRevision = 0
    private var activeRevision = 0
    private var nextOffset: Int?
    private var failedPagination = false
    private var submission = StudySubmissionState()
    private var pendingFinish: (id: UUID, endedAt: Date)?

    init(service: any StudyServiceProtocol, now: Date = .now, calendar: Calendar = .current) {
        self.service = service
        self.calendar = calendar
        self.month = now
    }

    var monthID: String { StudyRules.monthKey(month, calendar: calendar) }
    var filterID: String { "\(monthID)|\(query)|\(calendar.timeZone.identifier)" }

    func loadHistory() async {
        historyRevision += 1
        let revision = historyRevision
        let month = monthID
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        isLoading = true
        isLoadingMore = false
        historyErrorKey = nil
        failedPagination = false
        guard query.utf16.count <= 200 else {
            isLoading = false
            historyErrorKey = "record.error.search"
            return
        }
        do {
            let result = try await service.entries(month: month, timeZone: calendar.timeZone.identifier,
                                                   query: query, offset: 0, limit: 50)
            guard !Task.isCancelled, revision == historyRevision else { return }
            entries = unique(result.items)
            nextOffset = result.nextOffset
            hasMore = result.hasMore
            isLoading = false
        } catch {
            guard revision == historyRevision else { return }
            isLoading = false
            if !(error is CancellationError) { historyErrorKey = Self.errorKey(error) }
        }
    }

    func loadMore() async {
        guard !isLoading, !isLoadingMore, hasMore, let offset = nextOffset else { return }
        let revision = historyRevision
        let month = monthID
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        isLoadingMore = true
        historyErrorKey = nil
        do {
            let result = try await service.entries(month: month, timeZone: calendar.timeZone.identifier,
                                                   query: query, offset: offset, limit: 50)
            guard !Task.isCancelled, revision == historyRevision, nextOffset == offset else { return }
            entries = unique(entries + result.items)
            nextOffset = result.nextOffset
            hasMore = result.hasMore
            isLoadingMore = false
        } catch {
            guard revision == historyRevision else { return }
            isLoadingMore = false
            if !(error is CancellationError) {
                failedPagination = true
                historyErrorKey = Self.errorKey(error)
            }
        }
    }

    func retryHistory() async {
        if failedPagination { await loadMore() }
        else { await loadHistory() }
    }

    func loadOverview() async {
        overviewRevision += 1
        let revision = overviewRevision
        let month = monthID
        isLoadingOverview = true
        overviewErrorKey = nil
        do {
            let result = try await service.overview(month: month, timeZone: calendar.timeZone.identifier)
            guard !Task.isCancelled, revision == overviewRevision, month == monthID else { return }
            overview = result
            isLoadingOverview = false
        } catch {
            guard revision == overviewRevision else { return }
            isLoadingOverview = false
            if !(error is CancellationError) { overviewErrorKey = Self.errorKey(error) }
        }
    }

    /// 每次进入页面与回到前台向服务器恢复，计时显示只由该开始时间派生。
    func restoreActive() async {
        guard !isSaving else { return }
        activeRevision += 1
        let revision = activeRevision
        isRestoringActive = true
        activeErrorKey = nil
        do {
            let result = try await service.active()
            guard !Task.isCancelled, revision == activeRevision else { return }
            active = result
            isRestoringActive = false
            if pendingFinish?.id != result?.id { pendingFinish = nil }
        } catch {
            guard revision == activeRevision else { return }
            isRestoringActive = false
            if !(error is CancellationError) { activeErrorKey = Self.errorKey(error) }
        }
    }

    func refresh() async {
        async let history: Void = loadHistory()
        async let summary: Void = loadOverview()
        async let timer: Void = restoreActive()
        _ = await (history, summary, timer)
    }

    func create(title: String, start: Date? = nil, end: Date? = nil, now: Date = .now) async throws {
        guard !isSaving else { throw StudyValidationError.operationInProgress }
        guard StudyRules.isValidTitle(title) else { throw StudyValidationError.invalidTitle }
        if let end {
            guard let start, StudyRules.isValidInterval(start: start, end: end, now: now) else {
                throw StudyValidationError.invalidInterval
            }
        } else if active != nil {
            throw StudyValidationError.activeExists
        }
        let draft = submission.draft(title: title, start: start, end: end,
                                     zone: calendar.timeZone.identifier, now: now)
        isSaving = true
        activeRevision += 1
        isRestoringActive = false
        defer { isSaving = false }
        let saved = try await service.create(draft)
        submission.completed(id: draft.id)
        if saved.isActive {
            active = saved
            activeErrorKey = nil
        }
        await refreshContent()
    }

    /// 保留首次结束按钮的时间；响应丢失后重试不把额外等待时间计入记录。
    @discardableResult
    func finish(now: Date = .now) async -> Bool {
        guard !isSaving, let current = active else { return false }
        let end = pendingFinish?.id == current.id ? pendingFinish?.endedAt ?? now : now
        guard StudyRules.isValidInterval(start: current.startedAt, end: end, now: now) else {
            writeErrorKey = "record.error.interval"
            return false
        }
        pendingFinish = (current.id, end)
        isSaving = true
        activeRevision += 1
        isRestoringActive = false
        writeErrorKey = nil
        defer { isSaving = false }
        do {
            _ = try await service.finish(id: current.id, version: current.version, endedAt: end)
            pendingFinish = nil
            if active?.id == current.id { active = nil }
            await refreshContent()
            return true
        } catch {
            if !(error is CancellationError) { writeErrorKey = Self.errorKey(error) }
            return false
        }
    }

    func delete(_ entry: StudyEntry) async throws {
        guard !isSaving else { throw StudyValidationError.operationInProgress }
        isSaving = true
        activeRevision += 1
        isRestoringActive = false
        defer { isSaving = false }
        try await service.delete(id: entry.id, version: entry.version)
        if active?.id == entry.id { active = nil; pendingFinish = nil }
        await refreshContent()
    }

    private func refreshContent() async {
        async let history: Void = loadHistory()
        async let summary: Void = loadOverview()
        _ = await (history, summary)
    }

    private func invalidateHistory() {
        historyRevision += 1
        entries = []
        nextOffset = nil
        hasMore = false
        isLoading = true
        isLoadingMore = false
        historyErrorKey = nil
        failedPagination = false
    }

    private func unique(_ values: [StudyEntry]) -> [StudyEntry] {
        var ordered: [StudyEntry] = []
        var indices: [UUID: Int] = [:]
        for value in values {
            if let index = indices[value.id] { ordered[index] = value }
            else { indices[value.id] = ordered.count; ordered.append(value) }
        }
        return ordered
    }

    static func errorKey(_ error: Error) -> String {
        switch error {
        case APIClientError.conflict: return "api.error.conflict"
        case APIClientError.network: return "api.error.network"
        case APIClientError.unauthenticated: return "api.error.login"
        case APIClientError.invalidServer: return "api.error.server_address"
        case APIClientError.unavailable: return "api.error.unavailable"
        case APIClientError.invalidResponse: return "api.error.response"
        case StudyValidationError.invalidTitle: return "record.error.title"
        case StudyValidationError.invalidInterval: return "record.error.interval"
        case StudyValidationError.activeExists: return "record.error.active_exists"
        default: return "api.error.request"
        }
    }
}
