import XCTest
@testable import RecordAgent

final class ProactivePushTests: XCTestCase {
    func testSSEParserDecodesSnapshotUpdatedAndHeartbeat() {
        let json = """
        {"apnsConfigured":false,"apnsEnabled":false,"sandbox":true,"teamId":"","keyId":"","bundleId":"com.butvan.recordagent","registeredDeviceCount":1,"apnsTokenDeviceCount":0,"unacknowledgedCount":1,"devices":[],"pendingNotices":[{"id":"n-1","category":"COMPANION","kind":"COMPANION_OUTREACH","title":"Agent 主动问候","body":"晚上好","sessionId":"sess-1","routeTarget":"chat","createdAt":"2026-11-17T11:00:00Z","acknowledged":false,"apnsStatus":"NOT_CONFIGURED"}]}
        """
        let streamText = "event: snapshot\ndata: \(json)\n\nevent: heartbeat\ndata: ping\n\nevent: updated\ndata: \(json)\n\n"
        var parser = ProactivePushSSEParser()
        var events: [ProactivePushStreamEvent] = []
        for byte in streamText.utf8 {
            if let event = parser.feed(byte) {
                events.append(event)
            }
        }
        if let trailing = parser.finish() {
            events.append(trailing)
        }

        XCTAssertEqual(events.count, 3)
        guard case .snapshot(let snap) = events[0] else {
            return XCTFail("Expected snapshot")
        }
        XCTAssertEqual(snap.pendingNotices.first?.sessionId, "sess-1")
        XCTAssertEqual(events[1], .heartbeat)
        guard case .updated(let upd) = events[2] else {
            return XCTFail("Expected updated")
        }
        XCTAssertEqual(upd.unacknowledgedCount, 1)
    }

    func testNotificationPayloadParserSupportsApnsAndLocalFormats() {
        let apnsUserInfo: [AnyHashable: Any] = [
            "aps": ["alert": ["title": "Agent", "body": "Hi"]],
            "recordAgent": [
                "noticeId": "n-100",
                "sessionId": "sess-99",
                "routeTarget": "chat"
            ]
        ]
        let parsedApns = ProactiveNotificationPayloadParser.parse(userInfo: apnsUserInfo)
        XCTAssertEqual(parsedApns?.noticeId, "n-100")
        XCTAssertEqual(parsedApns?.routeTarget, .chat(sessionId: "sess-99"))

        let localTaskUserInfo: [AnyHashable: Any] = [
            "noticeId": "n-200",
            "sessionId": "",
            "routeTarget": "tasks"
        ]
        let parsedLocal = ProactiveNotificationPayloadParser.parse(userInfo: localTaskUserInfo)
        XCTAssertEqual(parsedLocal?.noticeId, "n-200")
        XCTAssertEqual(parsedLocal?.routeTarget, .tasks)
    }

    @MainActor
    func testNotificationCenterDeduplicatesNoticesAndHandlesPersonalSigningGracefully() async {
        let mockService = MockProactivePushService()
        let suiteName = "ProactivePushTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let center = ProactiveNotificationCenter(service: mockService, defaults: defaults)
        let notice = ProactiveNotice(
            id: "dup-1",
            category: "COMPANION",
            kind: "COMPANION_OUTREACH",
            title: "Agent 主动问候",
            body: "休息一下吧",
            sessionId: "s-1",
            routeTarget: "chat",
            createdAt: Date(),
            acknowledged: false,
            apnsStatus: "NOT_CONFIGURED"
        )

        await center.handleIncomingNotices([notice, notice], presentBanner: false)
        await center.handleIncomingNotices([notice], presentBanner: false)
        let ackCalls = await mockService.ackCalls
        XCTAssertEqual(ackCalls, ["dup-1"])

        let entitlementError = NSError(
            domain: NSCocoaErrorDomain,
            code: 3000,
            userInfo: [NSLocalizedDescriptionKey: "no valid “aps-environment” entitlement string found for application"]
        )
        center.handleFailedRemoteRegistration(entitlementError)
        if case .signingUnavailable = center.apnsState {
            // 符合预期：免费签名不报错阻塞
        } else {
            XCTFail("Expected signingUnavailable state, got \(center.apnsState)")
        }

        await center.activate(accountID: "server|user")
        await center.updatePreferences(ProactivePushPreferences(
            companionEnabled: true,
            signalEnabled: false,
            automationEnabled: true
        ))
        let lastPrefs = await mockService.lastRegisteredPreferences
        XCTAssertEqual(lastPrefs?.signalEnabled, false)
        XCTAssertEqual(lastPrefs?.companionEnabled, true)

        center.openNoticeRoute(notice)
        XCTAssertEqual(center.consumePendingRoute(), .chat(sessionId: "s-1"))
        XCTAssertNil(center.consumePendingRoute())
        await center.deactivate()
    }
}

private actor MockProactivePushService: ProactivePushServiceProtocol {
    var ackCalls: [String] = []
    var lastRegisteredPreferences: ProactivePushPreferences?

    func fetchStatus() async throws -> ProactivePushStatus {
        ProactivePushStatus(
            apnsConfigured: false,
            apnsEnabled: false,
            sandbox: true,
            teamId: "",
            keyId: "",
            bundleId: "com.butvan.recordagent",
            registeredDeviceCount: 1,
            apnsTokenDeviceCount: 0,
            unacknowledgedCount: 0,
            devices: [],
            pendingNotices: []
        )
    }

    func registerDevice(
        deviceId: String,
        deviceToken: String?,
        environment: String,
        appVersion: String,
        preferences: ProactivePushPreferences
    ) async throws -> ProactivePushStatus {
        lastRegisteredPreferences = preferences
        return try await fetchStatus()
    }

    func removeDevice(deviceId: String) async throws {}

    func fetchNotices(includeAcknowledged: Bool) async throws -> [ProactiveNotice] { [] }

    func acknowledgeNotice(id: String) async throws {
        ackCalls.append(id)
    }

    func sendTestPush(
        title: String?,
        body: String?,
        category: ProactivePushCategory
    ) async throws -> ProactiveTestPushResponse {
        let notice = ProactiveNotice(
            id: "test-1",
            category: category.rawValue,
            kind: "PUSH_TEST",
            title: title ?? "Test",
            body: body ?? "Body",
            sessionId: "",
            routeTarget: "chat",
            createdAt: Date(),
            acknowledged: false,
            apnsStatus: "NOT_CONFIGURED"
        )
        return ProactiveTestPushResponse(notice: notice, attemptedDevices: 0, apnsSentCount: 0, apnsSummary: "OK")
    }

    nonisolated func streamStatus() -> AsyncThrowingStream<ProactivePushStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            continuation.yield(.heartbeat)
            continuation.finish()
        }
    }
}
