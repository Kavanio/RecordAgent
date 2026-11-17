import Foundation

/// 直连 `agent-backend` `/agent/ios-push` 的设备登记、通知拉取与 SSE 实时推送服务。
final class APIProactivePushService: ProactivePushServiceProtocol, @unchecked Sendable {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func fetchStatus() async throws -> ProactivePushStatus {
        try await client.sendResult("/agent/ios-push/status")
    }

    func registerDevice(
        deviceId: String,
        deviceToken: String?,
        environment: String,
        appVersion: String,
        preferences: ProactivePushPreferences
    ) async throws -> ProactivePushStatus {
        let body = try APIClient.encoder.encode(RegisterDevicePayload(
            deviceId: deviceId,
            deviceToken: deviceToken,
            environment: environment,
            appVersion: appVersion,
            companionPushEnabled: preferences.companionEnabled,
            signalPushEnabled: preferences.signalEnabled,
            automationPushEnabled: preferences.automationEnabled
        ))
        return try await client.sendResult("/agent/ios-push/devices", method: "POST", body: body)
    }

    func removeDevice(deviceId: String) async throws {
        let escaped = deviceId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? deviceId
        let _: ProactivePushStatus? = try await client.sendOptionalResult(
            "/agent/ios-push/devices/\(escaped)",
            method: "DELETE"
        )
    }

    func fetchNotices(includeAcknowledged: Bool) async throws -> [ProactiveNotice] {
        try await client.sendResult(
            "/agent/ios-push/notices",
            query: [URLQueryItem(name: "includeAcknowledged", value: includeAcknowledged ? "true" : "false")]
        )
    }

    func acknowledgeNotice(id: String) async throws {
        let escaped = id.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? id
        let _: ProactivePushStatus? = try await client.sendOptionalResult(
            "/agent/ios-push/notices/\(escaped)/ack",
            method: "POST"
        )
    }

    func sendTestPush(
        title: String?,
        body: String?,
        category: ProactivePushCategory
    ) async throws -> ProactiveTestPushResponse {
        let payload = try APIClient.encoder.encode(TestPushPayload(
            title: title,
            body: body,
            category: category.rawValue
        ))
        return try await client.sendResult("/agent/ios-push/test", method: "POST", body: payload)
    }

    func streamStatus() -> AsyncThrowingStream<ProactivePushStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let bytes = try await client.openStreamBytes("/agent/ios-push/stream", method: "GET", body: nil)
                    var parser = ProactivePushSSEParser()
                    for try await byte in bytes {
                        try Task.checkCancellation()
                        if let event = parser.feed(byte) {
                            continuation.yield(event)
                        }
                    }
                    if let trailing = parser.finish() {
                        continuation.yield(trailing)
                    }
                    continuation.finish()
                } catch is CancellationError {
                    continuation.finish(throwing: CancellationError())
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in
                task.cancel()
            }
        }
    }
}

/// 逐字节解析 `/agent/ios-push/stream` SSE 事件帧（`snapshot`、`updated`、`heartbeat`）。
struct ProactivePushSSEParser {
    private var lineBuffer: [UInt8] = []
    private var currentEventName: String?
    private var dataLines: [String] = []
    private let decoder: JSONDecoder

    init(decoder: JSONDecoder = APIClient.decoder) {
        self.decoder = decoder
    }

    mutating func feed(_ byte: UInt8) -> ProactivePushStreamEvent? {
        if byte == 0x0A { // '\n'
            if lineBuffer.last == 0x0D { // '\r'
                lineBuffer.removeLast()
            }
            let line = String(decoding: lineBuffer, as: UTF8.self)
            lineBuffer.removeAll(keepingCapacity: true)
            return processLine(line)
        }
        lineBuffer.append(byte)
        return nil
    }

    mutating func finish() -> ProactivePushStreamEvent? {
        if !lineBuffer.isEmpty {
            if lineBuffer.last == 0x0D {
                lineBuffer.removeLast()
            }
            let line = String(decoding: lineBuffer, as: UTF8.self)
            lineBuffer.removeAll(keepingCapacity: false)
            if let event = processLine(line) {
                return event
            }
        }
        return flushEvent()
    }

    private mutating func processLine(_ line: String) -> ProactivePushStreamEvent? {
        if line.isEmpty {
            return flushEvent()
        }
        if line.hasPrefix(":") {
            return nil
        }
        if line.hasPrefix("event:") {
            let value = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
            currentEventName = value.isEmpty ? nil : value
            return nil
        }
        if line.hasPrefix("data:") {
            var content = String(line.dropFirst(5))
            if content.hasPrefix(" ") {
                content.removeFirst()
            }
            dataLines.append(content)
            return nil
        }
        return nil
    }

    private mutating func flushEvent() -> ProactivePushStreamEvent? {
        guard currentEventName != nil || !dataLines.isEmpty else {
            return nil
        }
        let name = currentEventName ?? "message"
        let payload = dataLines.joined(separator: "\n")
        currentEventName = nil
        dataLines.removeAll(keepingCapacity: true)

        switch name {
        case "snapshot":
            guard let data = payload.data(using: .utf8),
                  let status = try? decoder.decode(ProactivePushStatus.self, from: data) else {
                return nil
            }
            return .snapshot(status)
        case "updated":
            guard let data = payload.data(using: .utf8),
                  let status = try? decoder.decode(ProactivePushStatus.self, from: data) else {
                return nil
            }
            return .updated(status)
        case "heartbeat":
            return .heartbeat
        default:
            return nil
        }
    }
}

private struct RegisterDevicePayload: Encodable {
    let deviceId: String
    let deviceToken: String?
    let environment: String
    let appVersion: String
    let companionPushEnabled: Bool
    let signalPushEnabled: Bool
    let automationPushEnabled: Bool
}

private struct TestPushPayload: Encodable {
    let title: String?
    let body: String?
    let category: String
}
