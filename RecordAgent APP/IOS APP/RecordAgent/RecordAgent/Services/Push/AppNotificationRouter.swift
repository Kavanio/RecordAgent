import Foundation
import UserNotifications

/// 统一 `UNUserNotificationCenterDelegate` 路由：
/// 将定时任务本地提醒保留给 `LocalTaskNotificationService`，将主动问候与 APNs 远程推送交给 `ProactiveNotificationCenter`。
final class AppNotificationRouter: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    private let taskNotifications: LocalTaskNotificationService
    private let proactiveNotifications: ProactiveNotificationCenter

    init(
        taskNotifications: LocalTaskNotificationService,
        proactiveNotifications: ProactiveNotificationCenter,
        center: UNUserNotificationCenter = .current()
    ) {
        self.taskNotifications = taskNotifications
        self.proactiveNotifications = proactiveNotifications
        super.init()
        center.delegate = self
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if notification.request.identifier.hasPrefix("recordagent.task.") {
            taskNotifications.handleWillPresent(notification, completionHandler: completionHandler)
            return
        }
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.notification.request.identifier.hasPrefix("recordagent.task.") {
            taskNotifications.handleDidReceive(response, completionHandler: completionHandler)
            return
        }
        let userInfo = response.notification.request.content.userInfo
        Task { @MainActor [proactiveNotifications] in
            proactiveNotifications.handleNotificationTap(userInfo: userInfo)
            completionHandler()
        }
    }
}
