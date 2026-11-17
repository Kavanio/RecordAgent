import Foundation

/// Agent 会话、流式对话、权限审批与模型切换服务协议。
protocol AgentServiceProtocol: Sendable {
    func listSessions() async throws -> [AgentSessionSummary]
    func listFeishuSessions() async throws -> [AgentSessionSummary]
    func getSessionDetail(sessionId: String) async throws -> AgentSessionDetailDTO
    func getFeishuSessionDetail(sessionId: String) async throws -> AgentSessionDetailDTO
    func createSession(kind: AgentSessionKind, title: String, projectId: String?) async throws -> AgentSessionSummary
    func updateSessionTitle(sessionId: String, title: String) async throws -> AgentSessionSummary
    func generateSessionTitle(sessionId: String) async throws -> AgentSessionSummary?
    func deleteSession(sessionId: String) async throws
    func getPermissionMode(sessionId: String) async throws -> AgentSessionPermissionMode
    func updatePermissionMode(sessionId: String, mode: AgentSessionPermissionMode) async throws -> AgentSessionPermissionMode

    func fetchPendingPermission(sessionId: String) async throws -> AgentPendingApproval?
    func submitPermissionDecisions(
        sessionId: String,
        approvalId: String,
        decisions: [AgentPermissionDecisionInput]
    ) async throws -> AgentPermissionDecisionResponse
    func cancelRun(sessionId: String, runId: String) async throws -> AgentRunCancelResponse
    func fetchPlan(sessionId: String) async throws -> String?

    func streamChat(
        runId: String,
        sessionId: String,
        content: String,
        recordReferenceIds: [String]
    ) -> AsyncThrowingStream<AgentStreamEvent, Error>

    func resumeChat(
        sessionId: String,
        approvalId: String,
        runId: String
    ) -> AsyncThrowingStream<AgentStreamEvent, Error>

    func fetchModelConfig() async throws -> AgentModelConfig
    func fetchFullModelConfig() async throws -> AgentFullModelConfig
    func switchModel(
        vendor: String,
        modelName: String,
        apiKey: String,
        baseUrl: String?,
        protocolName: String?
    ) async throws
}
