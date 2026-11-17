import Foundation

/// iOS 主动推送与实时提醒服务契约（对接 `agent-backend` 的 `/agent/ios-push`）。
protocol ProactivePushServiceProtocol: Sendable {
    func fetchStatus() async throws -> ProactivePushStatus
    func registerDevice(
        deviceId: String,
        deviceToken: String?,
        environment: String,
        appVersion: String,
        preferences: ProactivePushPreferences
    ) async throws -> ProactivePushStatus
    func removeDevice(deviceId: String) async throws
    func fetchNotices(includeAcknowledged: Bool) async throws -> [ProactiveNotice]
    func acknowledgeNotice(id: String) async throws
    func sendTestPush(
        title: String?,
        body: String?,
        category: ProactivePushCategory
    ) async throws -> ProactiveTestPushResponse
    func streamStatus() -> AsyncThrowingStream<ProactivePushStreamEvent, Error>
}
