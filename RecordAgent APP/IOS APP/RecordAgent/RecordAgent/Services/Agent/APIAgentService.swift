import Foundation

/// 直连 `agent-backend/` 的 Agent 对话、会话管理、审批与模型配置服务。
final class APIAgentService: AgentServiceProtocol, @unchecked Sendable {
    private let client: APIClient

    init(client: APIClient) {
        self.client = client
    }

    func fetchReplyImage(fileId: String) async throws -> Data {
        guard (1...64).contains(fileId.count),
              fileId.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }) else {
            throw AgentReplyImageError.unavailable
        }
        let data = try await client.send("/agent/materials/\(fileId)/content")
        guard data.count <= AgentImageValidator.maxSingleBytes,
              AgentImageValidator.detectMediaType(in: data) != nil else {
            throw AgentReplyImageError.unavailable
        }
        return data
    }

    func listSessions() async throws -> [AgentSessionSummary] {
        try await client.sendResult("/agent/sessions")
    }

    func listFeishuSessions() async throws -> [AgentSessionSummary] {
        try await client.sendResult("/agent/sessions/feishu")
    }

    func getSessionDetail(sessionId: String) async throws -> AgentSessionDetailDTO {
        let escaped = Self.escapePathComponent(sessionId)
        return try await client.sendResult("/agent/sessions/\(escaped)")
    }

    func getFeishuSessionDetail(sessionId: String) async throws -> AgentSessionDetailDTO {
        let escaped = Self.escapePathComponent(sessionId)
        return try await client.sendResult("/agent/sessions/feishu/\(escaped)")
    }

    func createSession(
        kind: AgentSessionKind,
        title: String,
        projectId: String?
    ) async throws -> AgentSessionSummary {
        let body = try APIClient.encoder.encode(CreateSessionPayload(
            kind: kind,
            title: title,
            projectId: projectId
        ))
        return try await client.sendResult("/agent/sessions", method: "POST", body: body)
    }

    func updateSessionTitle(sessionId: String, title: String) async throws -> AgentSessionSummary {
        let escaped = Self.escapePathComponent(sessionId)
        let body = try APIClient.encoder.encode(UpdateSessionTitlePayload(title: title))
        return try await client.sendResult("/agent/sessions/\(escaped)", method: "PATCH", body: body)
    }

    func generateSessionTitle(sessionId: String) async throws -> AgentSessionSummary? {
        let escaped = Self.escapePathComponent(sessionId)
        return try await client.sendOptionalResult("/agent/sessions/\(escaped)/title/generate", method: "POST")
    }

    func deleteSession(sessionId: String) async throws {
        let escaped = Self.escapePathComponent(sessionId)
        let _: EmptyPayload? = try await client.sendOptionalResult("/agent/sessions/\(escaped)", method: "DELETE")
    }

    func getPermissionMode(sessionId: String) async throws -> AgentSessionPermissionMode {
        let escaped = Self.escapePathComponent(sessionId)
        let response: PermissionModePayload = try await client.sendResult(
            "/agent/sessions/\(escaped)/permission-mode"
        )
        return response.mode
    }

    func updatePermissionMode(
        sessionId: String,
        mode: AgentSessionPermissionMode
    ) async throws -> AgentSessionPermissionMode {
        let escaped = Self.escapePathComponent(sessionId)
        let body = try APIClient.encoder.encode(PermissionModePayload(mode: mode))
        let response: PermissionModePayload = try await client.sendResult(
            "/agent/sessions/\(escaped)/permission-mode",
            method: "PUT",
            body: body
        )
        return response.mode
    }

    func fetchPendingPermission(sessionId: String) async throws -> AgentPendingApproval? {
        let escaped = Self.escapePathComponent(sessionId)
        guard let data = try await client.sendRawAllowingEmpty(
            "/agent/chat/\(escaped)/permission/pending",
            emptyStatusCodes: [204]
        ) else {
            return nil
        }
        return try APIClient.decoder.decode(AgentPendingApproval.self, from: data)
    }

    func submitPermissionDecisions(
        sessionId: String,
        approvalId: String,
        decisions: [AgentPermissionDecisionInput]
    ) async throws -> AgentPermissionDecisionResponse {
        let body = try APIClient.encoder.encode(PermissionBatchDecisionPayload(
            sessionId: sessionId,
            approvalId: approvalId,
            decisions: decisions
        ))
        guard let data = try await client.sendRawAllowingEmpty(
            "/agent/chat/permission/decisions",
            method: "POST",
            body: body,
            emptyStatusCodes: []
        ) else {
            throw APIClientError.invalidResponse
        }
        return try APIClient.decoder.decode(AgentPermissionDecisionResponse.self, from: data)
    }

    func cancelRun(sessionId: String, runId: String) async throws -> AgentRunCancelResponse {
        let escapedRunId = Self.escapePathComponent(runId)
        let body = try APIClient.encoder.encode(RunCancelPayload(sessionId: sessionId))
        guard let data = try await client.sendRawAllowingEmpty(
            "/agent/chat/runs/\(escapedRunId)/cancel",
            method: "POST",
            body: body,
            emptyStatusCodes: []
        ) else {
            throw APIClientError.invalidResponse
        }
        return try APIClient.decoder.decode(AgentRunCancelResponse.self, from: data)
    }

    func fetchPlan(sessionId: String) async throws -> String? {
        let escaped = Self.escapePathComponent(sessionId)
        guard let data = try await client.sendRawAllowingEmpty(
            "/agent/chat/\(escaped)/plan",
            emptyStatusCodes: [204, 404]
        ) else {
            return nil
        }
        let payload = try APIClient.decoder.decode(PlanPayload.self, from: data)
        let trimmed = payload.content?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : payload.content
    }

    func fetchRecordReferences(query: String, limit: Int, offset: Int) async throws -> AgentRecordReferencePage {
        let items = [
            URLQueryItem(name: "query", value: query),
            URLQueryItem(name: "limit", value: String(limit)),
            URLQueryItem(name: "offset", value: String(offset)),
        ]
        return try await client.sendResult("/agent/records/references", query: items)
    }

    func fetchRecordDetail(id: String) async throws -> AgentRecordDetail {
        let escaped = Self.escapePathComponent(id)
        return try await client.sendResult("/agent/records/\(escaped)")
    }

    func streamChat(
        runId: String,
        sessionId: String,
        content: String,
        context: String,
        recordReferenceIds: [String],
        images: [AgentChatImagePayload]
    ) -> AsyncThrowingStream<AgentStreamEvent, Error> {
        let payload = StreamChatPayload(
            runId: runId,
            sessionId: sessionId,
            content: content,
            context: context,
            recordReferenceIds: recordReferenceIds,
            images: images
        )
        return openEventStream(path: "/agent/chat/stream", payload: payload)
    }

    func resumeChat(
        sessionId: String,
        approvalId: String,
        runId: String
    ) -> AsyncThrowingStream<AgentStreamEvent, Error> {
        let payload = ResumeChatPayload(
            sessionId: sessionId,
            approvalId: approvalId,
            runId: runId
        )
        return openEventStream(path: "/agent/chat/permission/resume", payload: payload)
    }

    func fetchModelConfig() async throws -> AgentModelConfig {
        try await client.sendResult("/agent/model/config")
    }

    func fetchFullModelConfig() async throws -> AgentFullModelConfig {
        try await client.sendResult("/agent/model/full-config")
    }

    func switchModel(
        vendor: String,
        modelName: String,
        apiKey: String,
        baseUrl: String?,
        protocolName: String?
    ) async throws {
        let body = try APIClient.encoder.encode(SetModelPayload(
            modelName: modelName,
            vendor: vendor,
            apiKey: apiKey,
            baseUrl: baseUrl,
            protocol: protocolName
        ))
        let _: String? = try await client.sendOptionalResult("/agent/model", method: "POST", body: body)
    }

    private func openEventStream<Body: Encodable & Sendable>(
        path: String,
        payload: Body
    ) -> AsyncThrowingStream<AgentStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let body = try APIClient.encoder.encode(payload)
                    let bytes = try await client.openStreamBytes(path, method: "POST", body: body)
                    var parser = SSELineParser()
                    var reachedTerminal = false

                    for try await byte in bytes {
                        try Task.checkCancellation()
                        if let event = parser.feed(byte) {
                            continuation.yield(event)
                            if Self.isTerminal(event) {
                                reachedTerminal = true
                                break
                            }
                        }
                    }

                    if !reachedTerminal, let trailing = parser.finish() {
                        continuation.yield(trailing)
                        if Self.isTerminal(trailing) {
                            reachedTerminal = true
                        }
                    }

                    if !reachedTerminal {
                        continuation.yield(.error(String(localized: "agent.error.unexpected_eof")))
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

    private static func isTerminal(_ event: AgentStreamEvent) -> Bool {
        switch event {
        case .done, .cancelled, .error, .permissionRequired:
            return true
        case .runStarted, .text, .thinking, .toolCall, .toolResult, .subagent, .routingNotice:
            return false
        }
    }

    private static func escapePathComponent(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? value
    }
}

private struct CreateSessionPayload: Encodable {
    let kind: AgentSessionKind
    let title: String
    let projectId: String?
}

private struct UpdateSessionTitlePayload: Encodable {
    let title: String
}

private struct PermissionModePayload: Codable {
    let mode: AgentSessionPermissionMode
}

private struct PermissionBatchDecisionPayload: Encodable {
    let sessionId: String
    let approvalId: String
    let decisions: [AgentPermissionDecisionInput]
}

private struct RunCancelPayload: Encodable {
    let sessionId: String
}

private struct PlanPayload: Decodable {
    let content: String?
}

private struct StreamChatPayload: Encodable {
    let runId: String
    let sessionId: String
    let content: String
    let context: String
    let recordReferenceIds: [String]
    let images: [AgentChatImagePayload]
}

private struct ResumeChatPayload: Encodable {
    let sessionId: String
    let approvalId: String
    let runId: String
}

private struct SetModelPayload: Encodable {
    let modelName: String
    let vendor: String
    let apiKey: String
    let baseUrl: String?
    let `protocol`: String?
}

private struct EmptyPayload: Decodable {}
