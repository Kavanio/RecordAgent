import Foundation
import UIKit
import UserNotifications

/// 统一管理 iOS 主动推送权限、APNs Token 注册兼容、前台 SSE 实时订阅、系统通知横幅展示与点击路由。
@MainActor
final class ProactiveNotificationCenter: ObservableObject {
    @Published private(set) var permission: MobileNotificationPermission = .notDetermined
    @Published private(set) var apnsState: APNSRegistrationState = .idle
    @Published private(set) var preferences: ProactivePushPreferences
    @Published private(set) var serverStatus: ProactivePushStatus?
    @Published private(set) var recentNotices: [ProactiveNotice] = []
    @Published private(set) var sseConnected = false
    @Published private(set) var pendingRoute: ProactivePushRouteTarget?
    @Published private(set) var latestNoticeSignal: Date?
    @Published private(set) var isSyncing = false
    @Published private(set) var lastSyncError: String?

    let deviceId: String

    private let service: any ProactivePushServiceProtocol
    private let notificationCenter: UNUserNotificationCenter
    private let defaults: UserDefaults
    private var accountID = ""
    private var rawDeviceToken: String?
    private var presentedNoticeIDs: Set<String> = []
    private var sseTask: Task<Void, Never>?
    private var syncGeneration = 0

    private static let deviceIDKey = "recordagent.ios_push.device_id"
    private static let preferencesKey = "recordagent.ios_push.preferences"

    init(
        service: any ProactivePushServiceProtocol,
        notificationCenter: UNUserNotificationCenter = .current(),
        defaults: UserDefaults = .standard
    ) {
        self.service = service
        self.notificationCenter = notificationCenter
        self.defaults = defaults

        if let savedID = defaults.string(forKey: Self.deviceIDKey), !savedID.isEmpty {
            self.deviceId = savedID
        } else {
            let created = UUID().uuidString.lowercased()
            defaults.set(created, forKey: Self.deviceIDKey)
            self.deviceId = created
        }

        if let data = defaults.data(forKey: Self.preferencesKey),
           let decoded = try? JSONDecoder().decode(ProactivePushPreferences.self, from: data) {
            self.preferences = decoded
        } else {
            self.preferences = .defaults
        }
    }

    var unreadNoticeCount: Int {
        recentNotices.filter { !$0.acknowledged }.count
    }

    /// 登录后或回到前台时激活：同步系统权限、尝试 APNs 注册、拉取待展示通知并建立 SSE 实时流。
    func activate(accountID: String) async {
        guard !accountID.isEmpty else { return }
        if self.accountID != accountID {
            self.accountID = accountID
            presentedNoticeIDs.removeAll()
        }
        syncGeneration += 1
        let expectedGen = syncGeneration

        await refreshPermission()
        if permission.canRegister {
            requestRemoteRegistrationIfPossible()
        }
        await syncDeviceAndPendingNotices(expectedGen: expectedGen)
        if syncGeneration == expectedGen {
            startSSEStream(expectedGen: expectedGen)
        }
    }

    /// App 退入后台时主动断开 SSE 长连接，待回到前台后再重连并补拉通知。
    func enterBackground() {
        sseTask?.cancel()
        sseTask = nil
        sseConnected = false
    }

    /// 退出登录或鉴权失效时清理连接并注销服务端设备绑定。
    func deactivate() async {
        syncGeneration += 1
        enterBackground()
        let hadAccount = !accountID.isEmpty
        accountID = ""
        serverStatus = nil
        recentNotices = []
        presentedNoticeIDs.removeAll()
        pendingRoute = nil
        lastSyncError = nil
        if hadAccount {
            try? await service.removeDevice(deviceId: deviceId)
        }
    }

    /// 用户手动在设置页点击“允许系统通知”。
    func requestNotificationPermission() async {
        await refreshPermission()
        if permission == .notDetermined {
            _ = try? await notificationCenter.requestAuthorization(options: [.alert, .sound, .badge])
            await refreshPermission()
        }
        if permission.canRegister {
            requestRemoteRegistrationIfPossible()
        }
    }

    /// 更新类别开关并立即同步到后端。
    func updatePreferences(_ updated: ProactivePushPreferences) async {
        preferences = updated
        if let encoded = try? JSONEncoder().encode(updated) {
            defaults.set(encoded, forKey: Self.preferencesKey)
        }
        await syncDeviceRegistrationOnly()
    }

    /// 发送测试通知（验证 APNs 与前台 SSE 双链路）。
    func sendTestPush() async -> ProactiveTestPushResponse? {
        do {
            let response = try await service.sendTestPush(title: nil, body: nil, category: .companion)
            await handleIncomingNotices([response.notice], presentBanner: true)
            await refreshNoticesHistory()
            return response
        } catch {
            lastSyncError = error.localizedDescription
            return nil
        }
    }

    func refreshNoticesHistory() async {
        do {
            let items = try await service.fetchNotices(includeAcknowledged: true)
            recentNotices = items
        } catch {
            lastSyncError = error.localizedDescription
        }
    }

    // MARK: - APNs 系统回调接入

    func handleRegisteredDeviceToken(_ tokenData: Data) {
        let hex = tokenData.map { String(format: "%02x", $0) }.joined()
        guard !hex.isEmpty else { return }
        rawDeviceToken = hex
        let prefix = hex.count >= 8 ? String(hex.prefix(8)) + "…" : hex
        apnsState = .registered(tokenPrefix: prefix)
        Task {
            await syncDeviceRegistrationOnly()
        }
    }

    func handleFailedRemoteRegistration(_ error: Error) {
        let nsError = error as NSError
        let description = error.localizedDescription.lowercased()
        let isEntitlementMissing =
            (nsError.domain == NSCocoaErrorDomain && nsError.code == 3000)
            || description.contains("aps-environment")
            || description.contains("entitlement")
            || description.contains("simulator")
        if isEntitlementMissing {
            apnsState = .signingUnavailable(detail: error.localizedDescription)
        } else {
            apnsState = .failed(detail: error.localizedDescription)
        }
    }

    /// 处理点击系统通知横幅（无论是 APNs 远程推送还是前台转化的本地通知）。
    func handleNotificationTap(userInfo: [AnyHashable: Any]) {
        guard let parsed = ProactiveNotificationPayloadParser.parse(userInfo: userInfo) else {
            return
        }
        pendingRoute = parsed.routeTarget
        if let noticeId = parsed.noticeId {
            Task {
                try? await service.acknowledgeNotice(id: noticeId)
                await refreshNoticesHistory()
            }
        }
    }

    func openNoticeRoute(_ notice: ProactiveNotice) {
        pendingRoute = notice.targetRoute
        if !notice.acknowledged {
            Task {
                try? await service.acknowledgeNotice(id: notice.id)
                await refreshNoticesHistory()
            }
        }
    }

    func consumePendingRoute() -> ProactivePushRouteTarget? {
        let route = pendingRoute
        pendingRoute = nil
        return route
    }

    // MARK: - 内部同步与 SSE 处理

    func refreshPermission() async {
        let settings = await notificationCenter.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized: permission = .authorized
        case .provisional, .ephemeral: permission = .provisional
        case .denied: permission = .denied
        case .notDetermined: permission = .notDetermined
        @unknown default: permission = .denied
        }
    }

    private func requestRemoteRegistrationIfPossible() {
        if case .idle = apnsState {
            apnsState = .registering
        }
        UIApplication.shared.registerForRemoteNotifications()
    }

    private func syncDeviceRegistrationOnly() async {
        guard !accountID.isEmpty else { return }
        do {
            let status = try await service.registerDevice(
                deviceId: deviceId,
                deviceToken: rawDeviceToken,
                environment: Self.currentApnsEnvironment,
                appVersion: Self.currentAppVersion,
                preferences: preferences
            )
            serverStatus = status
            lastSyncError = nil
        } catch {
            lastSyncError = error.localizedDescription
        }
    }

    private func syncDeviceAndPendingNotices(expectedGen: Int) async {
        isSyncing = true
        defer { isSyncing = false }
        do {
            let status = try await service.registerDevice(
                deviceId: deviceId,
                deviceToken: rawDeviceToken,
                environment: Self.currentApnsEnvironment,
                appVersion: Self.currentAppVersion,
                preferences: preferences
            )
            guard syncGeneration == expectedGen else { return }
            serverStatus = status
            lastSyncError = nil
            await handleIncomingNotices(status.pendingNotices, presentBanner: true)
            let history = try await service.fetchNotices(includeAcknowledged: true)
            guard syncGeneration == expectedGen else { return }
            recentNotices = history
        } catch is CancellationError {
        } catch {
            guard syncGeneration == expectedGen else { return }
            lastSyncError = error.localizedDescription
        }
    }

    private func startSSEStream(expectedGen: Int) {
        sseTask?.cancel()
        sseTask = Task { [weak self] in
            var backoffSeconds: UInt64 = 2
            while !Task.isCancelled {
                guard let self, self.syncGeneration == expectedGen, !self.accountID.isEmpty else { return }
                do {
                    let stream = self.service.streamStatus()
                    for try await event in stream {
                        guard !Task.isCancelled, self.syncGeneration == expectedGen else { return }
                        self.sseConnected = true
                        self.lastSyncError = nil
                        backoffSeconds = 2
                        switch event {
                        case .snapshot(let status), .updated(let status):
                            self.serverStatus = status
                            await self.handleIncomingNotices(status.pendingNotices, presentBanner: true)
                            await self.refreshNoticesHistory()
                        case .heartbeat:
                            break
                        }
                    }
                } catch is CancellationError {
                    return
                } catch APIClientError.unauthenticated {
                    self.sseConnected = false
                    return
                } catch {
                    self.sseConnected = false
                    self.lastSyncError = error.localizedDescription
                }
                guard !Task.isCancelled, self.syncGeneration == expectedGen else { return }
                try? await Task.sleep(for: .seconds(backoffSeconds))
                backoffSeconds = min(30, backoffSeconds * 2)
            }
        }
    }

    /// 对未确认的新主动通知去重后触发本地系统横幅提醒，并回执确认防止重复弹窗。
    func handleIncomingNotices(_ notices: [ProactiveNotice], presentBanner: Bool) async {
        var receivedNewCompanion = false
        for notice in notices where !notice.acknowledged {
            guard !presentedNoticeIDs.contains(notice.id) else { continue }
            presentedNoticeIDs.insert(notice.id)
            if notice.parsedCategory != .automation {
                receivedNewCompanion = true
            }
            if presentBanner && permission.canRegister {
                await scheduleLocalBanner(for: notice)
            }
            try? await service.acknowledgeNotice(id: notice.id)
        }
        if receivedNewCompanion {
            latestNoticeSignal = Date()
        }
    }

    private func scheduleLocalBanner(for notice: ProactiveNotice) async {
        let content = UNMutableNotificationContent()
        content.title = notice.title
        content.body = notice.body
        content.sound = .default
        content.userInfo = [
            "noticeId": notice.id,
            "sessionId": notice.sessionId,
            "routeTarget": notice.routeTarget
        ]

        let identifier = ProactiveNotificationPayloadParser.localIdentifierPrefix + notice.id
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 0.2, repeats: false)
        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
        try? await notificationCenter.add(request)
    }

    private static var currentApnsEnvironment: String {
        #if DEBUG
        return "sandbox"
        #else
        return "production"
        #endif
    }

    private static var currentAppVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
    }
}
