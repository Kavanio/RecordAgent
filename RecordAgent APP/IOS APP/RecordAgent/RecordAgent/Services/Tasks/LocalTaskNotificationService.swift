import CryptoKit
import Foundation
import UserNotifications

/// 系统通知代理只记录实际的系统通知对象，不把注册请求当作送达。
final class LocalTaskNotificationService: NSObject, MobileTaskNotificationProtocol, UNUserNotificationCenterDelegate, @unchecked Sendable {
    private let worker: LocalTaskNotificationWorker

    init(center: UNUserNotificationCenter = .current()) {
        worker = LocalTaskNotificationWorker(center: center)
        super.init()
        center.delegate = self
    }

    func activate(accountID: String) async -> UUID { await worker.activate(accountID: accountID) }
    func permission() async -> MobileNotificationPermission { await worker.permission() }
    func requestPermission() async throws -> MobileNotificationPermission { try await worker.requestPermission() }
    func reconcile(tasks: [MobileTask], accountID: String, sessionToken: UUID) async -> MobileTaskNotificationSnapshot {
        await worker.reconcile(tasks: tasks, accountID: accountID, sessionToken: sessionToken)
    }
    func observations(accountID: String, sessionToken: UUID) async -> [MobileTaskObservation] {
        await worker.observations(accountID: accountID, sessionToken: sessionToken)
    }
    func acknowledge(eventID: String) async { await worker.acknowledge(eventID: eventID) }
    func remove(taskID: String, accountID: String, sessionToken: UUID, includeDelivered: Bool) async {
        await worker.remove(taskID: taskID, accountID: accountID, sessionToken: sessionToken, includeDelivered: includeDelivered)
    }
    func clearAll() async { await worker.clearAll() }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        Task { await worker.remember(notification) }
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        Task {
            await worker.remember(response.notification)
            completionHandler()
        }
    }
}

/// 所有系统请求写入串行执行。会话令牌在网络读取前取得，退出清理会使旧读取失效。
private actor LocalTaskNotificationWorker {
    private let center: UNUserNotificationCenter
    private let managedPrefix = "recordagent.task."
    private var accountPrefix: String?
    private var sessionToken: UUID?
    private var observed: [String: MobileTaskObservation] = [:]
    private var mutationLocked = false
    private var mutationWaiters: [CheckedContinuation<Void, Never>] = []

    init(center: UNUserNotificationCenter) { self.center = center }

    func activate(accountID: String) async -> UUID {
        guard !Task.isCancelled else { return UUID() }
        await acquireMutation()
        defer { releaseMutation() }
        guard !Task.isCancelled else { return UUID() }
        let prefix = Self.prefix(accountID: accountID)
        let token = UUID()
        accountPrefix = prefix
        observed = observed.filter { $0.value.eventID.hasPrefix(prefix) }
        // 同账户重建页面也开启新生命周期，旧模型尚未返回的配置不得再写系统通知。
        sessionToken = token
        await removeAllManaged(except: prefix)
        return token
    }

    func permission() async -> MobileNotificationPermission {
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized: return .authorized
        case .provisional, .ephemeral: return .provisional
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .denied
        }
    }

    func requestPermission() async throws -> MobileNotificationPermission {
        if await permission() == .notDetermined {
            _ = try await center.requestAuthorization(options: [.alert, .sound, .badge])
        }
        return await permission()
    }

    func reconcile(tasks: [MobileTask], accountID: String, sessionToken token: UUID) async -> MobileTaskNotificationSnapshot {
        await acquireMutation()
        defer { releaseMutation() }
        let permission = await permission()
        var result = MobileTaskNotificationSnapshot(permission: permission, registrations: [:])
        let prefix = Self.prefix(accountID: accountID)
        guard accountPrefix == prefix, sessionToken == token, !Task.isCancelled else { return result }
        let pending = await center.pendingNotificationRequests()
        let enabled = Array(tasks.filter(\.enabled).prefix(32))
        let expected = Set(enabled.compactMap { task in
            MobileTaskSchedule.nextDate(for: task) == nil ? nil : Self.identifier(accountID: accountID, taskID: task.id)
        })
        let stale = pending.filter { $0.identifier.hasPrefix(managedPrefix) &&
            (!permission.canRegister || !expected.contains($0.identifier)) }.map(\.identifier)
        center.removePendingNotificationRequests(withIdentifiers: stale)
        let unmanagedCount = pending.filter { !$0.identifier.hasPrefix(managedPrefix) }.count
        let capacity = max(0, 64 - unmanagedCount)
        var added = 0
        for task in enabled {
            guard !Task.isCancelled else { break }
            guard MobileTaskSchedule.nextDate(for: task) != nil else {
                result.registrations[task.id] = .expired
                continue
            }
            guard permission.canRegister else {
                result.registrations[task.id] = permission == .denied ? .permissionDenied : .permissionRequired
                continue
            }
            guard added < capacity, let parts = try? MobileTaskSchedule.components(for: task) else {
                center.removePendingNotificationRequests(withIdentifiers: [Self.identifier(accountID: accountID, taskID: task.id)])
                result.registrations[task.id] = .failed
                continue
            }
            let content = UNMutableNotificationContent()
            content.title = task.title
            content.body = task.body
            content.sound = .default
            let identifier = Self.identifier(accountID: accountID, taskID: task.id)
            let request = UNNotificationRequest(identifier: identifier, content: content,
                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: task.recurrence != .once))
            do {
                // 相同 identifier 替换旧请求，编辑时间、时区和内容不会叠加通知。
                try await center.add(request)
                result.registrations[task.id] = .registered
                added += 1
            } catch {
                center.removePendingNotificationRequests(withIdentifiers: [identifier])
                result.registrations[task.id] = .failed
            }
        }
        for task in tasks where !task.enabled { result.registrations[task.id] = .paused }
        return result
    }

    func observations(accountID: String, sessionToken token: UUID) async -> [MobileTaskObservation] {
        let prefix = Self.prefix(accountID: accountID)
        guard accountPrefix == prefix, sessionToken == token else { return [] }
        let delivered = await center.deliveredNotifications()
        guard accountPrefix == prefix, sessionToken == token else { return [] }
        for notification in delivered {
            if let observation = Self.observation(notification, prefix: prefix) {
                observed[observation.eventID] = observation
            }
        }
        return observed.values.filter { $0.eventID.hasPrefix(prefix) }.sorted { $0.deliveredAt < $1.deliveredAt }
    }

    func remember(_ notification: UNNotification) {
        let identifier = notification.request.identifier
        let pieces = identifier.split(separator: ".")
        guard pieces.count == 4, identifier.hasPrefix(managedPrefix), pieces[2].count == 32 else { return }
        let prefix = accountPrefix ?? "recordagent.task.\(pieces[2])."
        // 冷启动点击先保留真实系统证据；登录后只上传与已核验账户摘要匹配的观察。
        guard let observation = Self.observation(notification, prefix: prefix) else { return }
        observed[observation.eventID] = observation
        if observed.count > 64, let oldest = observed.values.min(by: { $0.deliveredAt < $1.deliveredAt }) {
            observed[oldest.eventID] = nil
        }
    }

    func acknowledge(eventID: String) { observed[eventID] = nil }

    func remove(taskID: String, accountID: String, sessionToken token: UUID, includeDelivered: Bool) async {
        await acquireMutation()
        defer { releaseMutation() }
        guard accountPrefix == Self.prefix(accountID: accountID), sessionToken == token else { return }
        let identifier = Self.identifier(accountID: accountID, taskID: taskID)
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        if includeDelivered {
            center.removeDeliveredNotifications(withIdentifiers: [identifier])
            observed = observed.filter { $0.value.taskID.lowercased() != taskID.lowercased() }
        }
    }

    func clearAll() async {
        await acquireMutation()
        defer { releaseMutation() }
        sessionToken = nil
        accountPrefix = nil
        observed.removeAll()
        await removeAllManaged()
    }

    private func removeAllManaged(except keptPrefix: String? = nil) async {
        let pending = await center.pendingNotificationRequests()
        center.removePendingNotificationRequests(withIdentifiers: pending.filter {
            $0.identifier.hasPrefix(managedPrefix) && !(keptPrefix.map($0.identifier.hasPrefix) ?? false)
        }.map(\.identifier))
        let delivered = await center.deliveredNotifications()
        center.removeDeliveredNotifications(withIdentifiers: delivered.filter {
            $0.request.identifier.hasPrefix(managedPrefix) && !(keptPrefix.map($0.request.identifier.hasPrefix) ?? false)
        }.map { $0.request.identifier })
    }

    private func acquireMutation() async {
        if !mutationLocked { mutationLocked = true; return }
        await withCheckedContinuation { mutationWaiters.append($0) }
    }

    private func releaseMutation() {
        if mutationWaiters.isEmpty { mutationLocked = false }
        else { mutationWaiters.removeFirst().resume() }
    }

    private static func prefix(accountID: String) -> String {
        let digest = SHA256.hash(data: Data(accountID.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
        return "recordagent.task.\(digest)."
    }

    private static func identifier(accountID: String, taskID: String) -> String {
        "\(prefix(accountID: accountID))\(taskID.lowercased())"
    }

    private static func observation(_ notification: UNNotification, prefix: String) -> MobileTaskObservation? {
        let identifier = notification.request.identifier
        guard identifier.hasPrefix(prefix) else { return nil }
        let taskID = String(identifier.dropFirst(prefix.count))
        guard UUID(uuidString: taskID) != nil else { return nil }
        let milliseconds = Int64((notification.date.timeIntervalSince1970 * 1000).rounded())
        return MobileTaskObservation(taskID: taskID, eventID: "\(identifier).\(milliseconds)", deliveredAt: notification.date)
    }
}
