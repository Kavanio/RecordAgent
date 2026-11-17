import UIKit

/// 接收系统 APNs Device Token 注册结果并转发给主动推送中心；
/// 在免费个人签名下系统会返回缺失 aps-environment 回调，交由上层平稳降级。
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Task { @MainActor in
            AppEnvironment.shared.proactiveNotifications.handleRegisteredDeviceToken(deviceToken)
        }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        Task { @MainActor in
            AppEnvironment.shared.proactiveNotifications.handleFailedRemoteRegistration(error)
        }
    }
}
