import Foundation
import SwiftUI

/// 后端主动推送的三大事件类别。
enum ProactivePushCategory: String, Codable, CaseIterable, Identifiable, Sendable {
    case companion = "COMPANION"
    case signal = "SIGNAL"
    case automation = "AUTOMATION"

    var id: String { rawValue }

    var titleKey: LocalizedStringKey {
        switch self {
        case .companion: return "push.category.companion.title"
        case .signal: return "push.category.signal.title"
        case .automation: return "push.category.automation.title"
        }
    }

    var descriptionKey: LocalizedStringKey {
        switch self {
        case .companion: return "push.category.companion.description"
        case .signal: return "push.category.signal.description"
        case .automation: return "push.category.automation.description"
        }
    }
}

/// 本地持久化的主动推送类别偏好。
struct ProactivePushPreferences: Codable, Equatable, Sendable {
    var companionEnabled: Bool
    var signalEnabled: Bool
    var automationEnabled: Bool

    static let defaults = ProactivePushPreferences(
        companionEnabled: true,
        signalEnabled: true,
        automationEnabled: true
    )
}

/// 单条后端推送给 iOS 的主动通知记录。
struct ProactiveNotice: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let category: String
    let kind: String
    let title: String
    let body: String
    let sessionId: String
    let routeTarget: String
    let createdAt: Date
    let acknowledged: Bool
    let apnsStatus: String

    var parsedCategory: ProactivePushCategory {
        ProactivePushCategory(rawValue: category.uppercased()) ?? .companion
    }

    var targetRoute: ProactivePushRouteTarget {
        switch routeTarget.lowercased() {
        case "tasks":
            return .tasks
        case "home":
            return .home
        default:
            let trimmed = sessionId.trimmingCharacters(in: .whitespacesAndNewlines)
            return .chat(sessionId: trimmed.isEmpty ? nil : trimmed)
        }
    }
}

/// 服务端已登记设备摘要（脱敏，不含完整 Device Token）。
struct ProactivePushDeviceSummary: Identifiable, Codable, Equatable, Sendable {
    let deviceId: String
    let hasApnsToken: Bool
    let tokenPrefix: String
    let environment: String
    let appVersion: String
    let companionPushEnabled: Bool
    let signalPushEnabled: Bool
    let automationPushEnabled: Bool
    let updatedAt: Date?

    var id: String { deviceId }
}

/// 服务端 `/agent/ios-push/status` 及 SSE 快照。
struct ProactivePushStatus: Codable, Equatable, Sendable {
    let apnsConfigured: Bool
    let apnsEnabled: Bool
    let sandbox: Bool
    let teamId: String
    let keyId: String
    let bundleId: String
    let registeredDeviceCount: Int
    let apnsTokenDeviceCount: Int
    let unacknowledgedCount: Int
    let devices: [ProactivePushDeviceSummary]
    let pendingNotices: [ProactiveNotice]
}

/// 测试推送响应。
struct ProactiveTestPushResponse: Codable, Equatable, Sendable {
    let notice: ProactiveNotice
    let attemptedDevices: Int
    let apnsSentCount: Int
    let apnsSummary: String
}

/// 主动推送 SSE 事件。
enum ProactivePushStreamEvent: Equatable, Sendable {
    case snapshot(ProactivePushStatus)
    case updated(ProactivePushStatus)
    case heartbeat
}

/// 点击系统通知或通知列表条目后的应用内路由目标。
enum ProactivePushRouteTarget: Equatable, Sendable {
    case chat(sessionId: String?)
    case tasks
    case home
}

/// 当前设备的 APNs 注册状态（兼容个人免费签名，不因无推送权限而阻断应用）。
enum APNSRegistrationState: Equatable, Sendable {
    case idle
    case registering
    case registered(tokenPrefix: String)
    case signingUnavailable(detail: String)
    case failed(detail: String)
}

/// 解析来自 APNs `aps/recordAgent` 或本地系统通知 `userInfo` 的路由信息。
enum ProactiveNotificationPayloadParser {
    static let localIdentifierPrefix = "recordagent.proactive."

    struct ParsedPayload: Equatable, Sendable {
        let noticeId: String?
        let routeTarget: ProactivePushRouteTarget
    }

    static func parse(userInfo: [AnyHashable: Any]) -> ParsedPayload? {
        if let meta = userInfo["recordAgent"] as? [AnyHashable: Any] {
            return parseDictionary(meta)
        }
        return parseDictionary(userInfo)
    }

    private static func parseDictionary(_ dict: [AnyHashable: Any]) -> ParsedPayload? {
        let noticeId = (dict["noticeId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let routeRaw = (dict["routeTarget"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let sessionRaw = (dict["sessionId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedNoticeId = (noticeId?.isEmpty == false) ? noticeId : nil
        let normalizedSessionId = (sessionRaw?.isEmpty == false) ? sessionRaw : nil

        guard normalizedNoticeId != nil || routeRaw != nil || normalizedSessionId != nil else {
            return nil
        }

        let target: ProactivePushRouteTarget
        switch routeRaw?.lowercased() {
        case "tasks":
            target = .tasks
        case "home":
            target = .home
        default:
            target = .chat(sessionId: normalizedSessionId)
        }
        return ParsedPayload(noticeId: normalizedNoticeId, routeTarget: target)
    }
}
