import Foundation
import SwiftUI

@MainActor
final class MobileTaskViewModel: ObservableObject {
    @Published var query = ""
    @Published var filter: MobileTaskFilter = .all
    @Published private(set) var tasks: [MobileTask] = []
    @Published private(set) var selectedTask: MobileTask?
    @Published private(set) var deliveries: [MobileTaskDelivery] = []
    @Published private(set) var isLoading = false
    @Published private(set) var isLoadingHistory = false
    @Published private(set) var isSaving = false
    @Published private(set) var hasMore = false
    @Published private(set) var historyHasMore = false
    @Published private(set) var loadError: String?
    @Published private(set) var historyError: String?
    @Published private(set) var synchronizationFailed = false
    @Published private(set) var observationUploadFailed = false
    @Published private(set) var permission: MobileNotificationPermission = .notDetermined
    @Published private(set) var registrations: [String: MobileTaskRegistration] = [:]
    private let service: any MobileTaskServiceProtocol
    private let notifications: any MobileTaskNotificationProtocol
    private let accountID: String
    private var nextOffset = 0
    private var historyOffset = 0
    private var loadRevision = UUID()
    private var historyRevision = UUID()
    private var lifecycleRevision = UUID()
    private var sessionToken: UUID?
    private var active = true
    private var synchronization: Task<Void, Never>?
    private var synchronizationID = UUID()

    init(service: any MobileTaskServiceProtocol, notifications: any MobileTaskNotificationProtocol, accountID: String) {
        self.service = service
        self.notifications = notifications
        self.accountID = accountID
    }

    var requestKey: String { "\(filter.rawValue)|\(query)" }

    func load() async {
        guard active else { return }
        let revision = UUID()
        loadRevision = revision
        isLoading = true
        loadError = nil
        tasks = []
        hasMore = false
        nextOffset = 0
        do {
            let page = try await service.list(query: query, enabled: filter.enabled, offset: 0, limit: 40)
            guard active, loadRevision == revision, !Task.isCancelled else { return }
            tasks = page.items
            hasMore = page.hasMore
            nextOffset = page.nextOffset ?? page.items.count
        } catch is CancellationError {
        } catch {
            guard active, loadRevision == revision, !Task.isCancelled else { return }
            loadError = error.localizedDescription
        }
        if loadRevision == revision { isLoading = false }
    }

    func loadMore() async {
        guard active, hasMore, !isLoading else { return }
        let revision = loadRevision
        isLoading = true
        loadError = nil
        do {
            let page = try await service.list(query: query, enabled: filter.enabled, offset: nextOffset, limit: 40)
            guard active, loadRevision == revision, !Task.isCancelled else { return }
            let existing = Set(tasks.map(\.id))
            tasks.append(contentsOf: page.items.filter { !existing.contains($0.id) })
            hasMore = page.hasMore
            nextOffset = page.nextOffset ?? nextOffset + page.items.count
        } catch is CancellationError {
        } catch {
            guard active, loadRevision == revision else { return }
            loadError = error.localizedDescription
        }
        if loadRevision == revision { isLoading = false }
    }

    func open(_ task: MobileTask) async {
        guard active else { return }
        selectedTask = task
        deliveries = []
        historyOffset = 0
        historyHasMore = false
        await reloadDetail()
    }

    func reloadDetail() async {
        guard active, let selectedTask else { return }
        let revision = UUID()
        historyRevision = revision
        isLoadingHistory = true
        historyError = nil
        do {
            let latest = try await service.task(id: selectedTask.id)
            guard active, historyRevision == revision, self.selectedTask?.id == selectedTask.id else { return }
            self.selectedTask = latest
            let page = try await service.deliveries(taskID: latest.id, offset: 0, limit: 30)
            guard active, historyRevision == revision else { return }
            deliveries = page.items
            historyHasMore = page.hasMore
            historyOffset = page.nextOffset ?? page.items.count
        } catch is CancellationError {
        } catch {
            guard active, historyRevision == revision else { return }
            historyError = error.localizedDescription
        }
        if historyRevision == revision { isLoadingHistory = false }
    }

    func loadMoreHistory() async {
        guard active, let selectedTask, historyHasMore, !isLoadingHistory else { return }
        let revision = historyRevision
        isLoadingHistory = true
        historyError = nil
        do {
            let page = try await service.deliveries(taskID: selectedTask.id, offset: historyOffset, limit: 30)
            guard active, historyRevision == revision else { return }
            let existing = Set(deliveries.map(\.id))
            deliveries.append(contentsOf: page.items.filter { !existing.contains($0.id) })
            historyHasMore = page.hasMore
            historyOffset = page.nextOffset ?? historyOffset + page.items.count
        } catch is CancellationError {
        } catch {
            guard active, historyRevision == revision else { return }
            historyError = error.localizedDescription
        }
        if historyRevision == revision { isLoadingHistory = false }
    }

    func save(_ draft: MobileTask, creating: Bool) async throws {
        guard active, !isSaving else { throw CancellationError() }
        let revision = lifecycleRevision
        isSaving = true
        defer { isSaving = false }
        let saved = try await service.save(draft, creating: creating)
        guard active, lifecycleRevision == revision else { throw CancellationError() }
        if selectedTask?.id == saved.id { selectedTask = saved }
        // 保存配置成功后才按用户此次明确开启提醒的意图申请权限。
        if saved.enabled {
            do { permission = try await notifications.requestPermission() }
            catch { synchronizationFailed = true }
            guard active, lifecycleRevision == revision else { throw CancellationError() }
        } else {
            await removeLocalRequest(taskID: saved.id, includeDelivered: false, revision: revision)
        }
        await synchronizeNotifications(force: true)
        guard active, lifecycleRevision == revision else { throw CancellationError() }
        if !saved.enabled { await removeLocalRequest(taskID: saved.id, includeDelivered: false, revision: revision) }
        await load()
        if selectedTask?.id == saved.id { await reloadDetail() }
    }

    func toggle(_ task: MobileTask) async throws {
        var draft = task
        draft.enabled.toggle()
        try await save(draft, creating: false)
    }

    func delete(_ task: MobileTask) async throws {
        guard active, !isSaving else { throw CancellationError() }
        let revision = lifecycleRevision
        isSaving = true
        defer { isSaving = false }
        try await service.delete(id: task.id, version: task.version)
        guard active, lifecycleRevision == revision else { throw CancellationError() }
        if selectedTask?.id == task.id { selectedTask = nil }
        await removeLocalRequest(taskID: task.id, includeDelivered: true, revision: revision)
        await synchronizeNotifications(force: true)
        await removeLocalRequest(taskID: task.id, includeDelivered: true, revision: revision)
        await load()
    }

    func registration(for task: MobileTask) -> MobileTaskRegistration {
        guard task.enabled else { return .paused }
        guard MobileTaskSchedule.nextDate(for: task) != nil else { return .expired }
        if permission == .denied { return .permissionDenied }
        if permission == .notDetermined { return .permissionRequired }
        return registrations[task.id] ?? .unknown
    }

    private func removeLocalRequest(taskID: String, includeDelivered: Bool, revision: UUID) async {
        guard active, lifecycleRevision == revision else { return }
        if sessionToken == nil { sessionToken = await notifications.activate(accountID: accountID) }
        guard active, lifecycleRevision == revision, let sessionToken else { return }
        await notifications.remove(taskID: taskID, accountID: accountID, sessionToken: sessionToken, includeDelivered: includeDelivered)
    }

    func requestNotificationPermission() async {
        guard active else { return }
        do { permission = try await notifications.requestPermission() }
        catch { synchronizationFailed = true }
        await synchronizeNotifications(force: true)
    }

    /// 登录、前台恢复及保存后读取权威配置；此路径从不弹出系统权限请求。
    func synchronizeNotifications(force: Bool = false) async {
        guard active, !Task.isCancelled else { return }
        if let synchronization {
            let previousID = synchronizationID
            await synchronization.value
            if synchronizationID == previousID { self.synchronization = nil }
            if force, active { await synchronizeNotifications(force: true) }
            return
        }
        let revision = lifecycleRevision
        let operationID = UUID()
        synchronizationID = operationID
        let operation = Task<Void, Never> { [weak self] in
            guard let self else { return }
            await self.performSynchronization(revision: revision)
        }
        synchronization = operation
        await withTaskCancellationHandler {
            await operation.value
        } onCancel: {
            operation.cancel()
        }
        if synchronizationID == operationID { synchronization = nil }
    }

    private func performSynchronization(revision: UUID) async {
        guard active, lifecycleRevision == revision, !Task.isCancelled else { return }
        if sessionToken == nil { sessionToken = await notifications.activate(accountID: accountID) }
        guard active, lifecycleRevision == revision, !Task.isCancelled, let sessionToken else { return }
        permission = await notifications.permission()
        guard active, lifecycleRevision == revision, !Task.isCancelled else { return }
        registrations = [:]
        synchronizationFailed = false
        observationUploadFailed = false
        if !permission.canRegister {
            _ = await notifications.reconcile(tasks: [], accountID: accountID, sessionToken: sessionToken)
        }
        do {
            let enabled = try await service.enabledTasks()
            guard active, lifecycleRevision == revision, !Task.isCancelled else { return }
            let snapshot = await notifications.reconcile(tasks: enabled, accountID: accountID, sessionToken: sessionToken)
            guard active, lifecycleRevision == revision else { return }
            permission = snapshot.permission
            registrations = snapshot.registrations
        } catch is CancellationError {
            return
        } catch {
            guard active, lifecycleRevision == revision else { return }
            synchronizationFailed = true
        }
        let observations = await notifications.observations(accountID: accountID, sessionToken: sessionToken)
        for observation in observations {
            guard active, lifecycleRevision == revision, !Task.isCancelled else { return }
            do {
                try await service.observe(observation)
                guard active, lifecycleRevision == revision else { return }
                await notifications.acknowledge(eventID: observation.eventID)
            } catch is CancellationError {
                return
            } catch APIClientError.server(_, 404) {
                // 他端已删除的任务不再有可写历史，丢弃这条旧设备观察。
                await notifications.acknowledge(eventID: observation.eventID)
            } catch {
                observationUploadFailed = true
            }
        }
    }

    /// 先废弃页面与网络结果，再清理系统请求；旧同步不会在退出后重新注册。
    func deactivate() async {
        active = false
        lifecycleRevision = UUID()
        loadRevision = UUID()
        historyRevision = UUID()
        synchronization?.cancel()
        tasks = []
        selectedTask = nil
        deliveries = []
        registrations = [:]
        await notifications.clearAll()
    }
}
