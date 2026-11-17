import XCTest
@testable import RecordAgent

final class AgentTests: XCTestCase {
    func testSSELineParserPreservesMultiLineTextAndToolEvents() {
        let stream = """
        event:run_started
        data:{"runId":"run-1"}

        event:thinking
        data:先思考一下

        event:tool_call
        data:{"toolCallId":"tc-1","toolName":"read_file","command":"notes.md"}

        event:tool_result
        data:{"toolCallId":"tc-1","toolName":"read_file","result":"hello","status":"completed"}

        event:text
        data:第一行
        data:第二行

        event:done
        data:

        """

        var parser = SSELineParser()
        var events: [AgentStreamEvent] = []
        for byte in stream.utf8 {
            if let event = parser.feed(byte) {
                events.append(event)
            }
        }
        if let trailing = parser.finish() {
            events.append(trailing)
        }

        XCTAssertEqual(events.count, 6)
        XCTAssertEqual(events[0], .runStarted(runId: "run-1"))
        XCTAssertEqual(events[1], .thinking("先思考一下"))
        XCTAssertEqual(events[2], .toolCall(toolCallId: "tc-1", toolName: "read_file", command: "notes.md"))
        XCTAssertEqual(events[3], .toolResult(toolCallId: "tc-1", toolName: "read_file", result: "hello", status: .completed))
        XCTAssertEqual(events[4], .text("第一行\n第二行"))
        XCTAssertEqual(events[5], .done)
    }

    func testTimelineBuilderInterleavesBlocksAndSplitsFinalAnswer() {
        var message = AgentChatMessage(
            id: "msg-1",
            turnId: "turn-1",
            role: .assistant,
            content: "",
            thinking: nil,
            createdAt: Date(),
            status: nil,
            durationSeconds: nil,
            tools: [],
            blocks: [],
            proactive: false,
            usage: nil,
            isStreaming: true
        )

        message = AgentTimelineBuilder.appendThinking(message, chunk: "检查日历")
        message = AgentTimelineBuilder.upsertToolCall(
            message,
            toolCallId: "t-1",
            toolName: "query_database",
            command: "SELECT *"
        )
        message = AgentTimelineBuilder.applyToolResult(
            message,
            toolCallId: "t-1",
            result: "2 rows",
            status: .completed
        )
        message = AgentTimelineBuilder.appendText(message, chunk: "今天共有两项待办。")

        let split = AgentTimelineBuilder.splitProcessAndFinalBlocks(for: message)
        XCTAssertTrue(split.hasProcessTrace)
        XCTAssertEqual(split.processBlocks.count, 2)
        XCTAssertEqual(split.processBlocks[0].kind, .thinking)
        XCTAssertEqual(split.processBlocks[1].kind, .toolGroup)
        XCTAssertEqual(split.processBlocks[1].tools.first?.status, .completed)
        XCTAssertEqual(split.finalText, "今天共有两项待办。")
    }

    func testTranscriptMappingSupportsBlocksAndProactiveDetection() {
        let dtos: [AgentTranscriptMessageDTO] = [
            AgentTranscriptMessageDTO(
                id: "u-1",
                turnId: "turn-a",
                role: .user,
                content: "你好",
                createdAt: Date(),
                status: .completed,
                durationMillis: nil,
                tools: nil,
                thinking: nil,
                usage: nil,
                proactive: nil,
                blocks: nil
            ),
            AgentTranscriptMessageDTO(
                id: "a-1",
                turnId: "turn-a",
                role: .assistant,
                content: "最终回答",
                createdAt: Date(),
                status: .completed,
                durationMillis: 2300,
                tools: nil,
                thinking: "内部思考",
                usage: nil,
                proactive: false,
                blocks: [
                    AgentTranscriptBlockDTO(kind: .thinking, content: "内部思考", tools: nil),
                    AgentTranscriptBlockDTO(kind: .text, content: "最终回答", tools: nil)
                ]
            ),
            AgentTranscriptMessageDTO(
                id: "a-2",
                turnId: "proactive-turn",
                role: .assistant,
                content: "该休息一下了",
                createdAt: Date(),
                status: .completed,
                durationMillis: 0,
                tools: nil,
                thinking: nil,
                usage: nil,
                proactive: nil,
                blocks: nil
            )
        ]

        let mapped = AgentTimelineBuilder.mapTranscriptMessages(dtos)
        XCTAssertEqual(mapped.count, 3)
        XCTAssertFalse(mapped[1].proactive)
        XCTAssertEqual(mapped[1].durationSeconds, 2)
        XCTAssertEqual(mapped[1].blocks.count, 2)
        XCTAssertTrue(mapped[2].proactive)
    }

    @MainActor
    func testSendMessageCreatesSessionStreamsAndGeneratesTitle() async throws {
        let mock = MockAgentService()
        let viewModel = AgentChatViewModel(service: mock)

        await viewModel.sendPrompt("帮我看看今天的待办")
        // 等待流式异步任务结束
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertEqual(viewModel.activeSessionId, "sess-created")
        XCTAssertFalse(viewModel.isStreaming)
        XCTAssertEqual(viewModel.messages.last?.content, "这是来自服务器的回复")
        let titleGeneratedCount = await mock.generatedTitleCalls
        XCTAssertEqual(titleGeneratedCount, 1)
    }

    @MainActor
    func testPermissionApprovalSubmitsBatchAndResumesStream() async throws {
        let mock = MockAgentService()
        await mock.setRequirePermissionOnStream(true)
        let viewModel = AgentChatViewModel(service: mock)

        await viewModel.sendPrompt("修改文件")
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertNotNil(viewModel.pendingApproval)
        XCTAssertFalse(viewModel.isStreaming)

        await viewModel.submitApprovalDecisions(overrideAllApproved: true)
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertNil(viewModel.pendingApproval)
        XCTAssertFalse(viewModel.isStreaming)
        XCTAssertTrue(viewModel.messages.last?.content.contains("已恢复执行完成") == true)
        let decisionCalls = await mock.submittedApprovalIds
        XCTAssertEqual(decisionCalls, ["appr-1"])
    }

    func testAPIClientFetchesAndAttachesAgentGenerationHeader() async throws {
        let previousAddress = APIConfiguration.savedServerAddress()
        addTeardownBlock {
            if previousAddress.isEmpty {
                APIConfiguration.clearServerAddress()
            } else {
                _ = try? APIConfiguration.saveServerAddress(previousAddress)
            }
            AgentGenerationURLProtocol.handler = nil
        }

        let testServer = "http://127.0.0.1:18081"
        _ = try APIConfiguration.saveServerAddress(testServer)

        var seenPaths: [String] = []
        var generationHeaders: [String: String?] = [:]
        AgentGenerationURLProtocol.handler = { request in
            let path = request.url?.path ?? ""
            seenPaths.append("\(request.httpMethod ?? "GET") \(path)")
            generationHeaders[path] = request.value(forHTTPHeaderField: "X-Agent-Generation")
            let body: Data
            if path == "/agent/reset/current" {
                body = Data(#"{"code":200,"data":{"resetId":null,"revision":1,"generation":7,"status":"IDLE","completedSteps":[],"message":"","retryable":false,"remoteStatus":null}}"#.utf8)
            } else {
                body = Data(#"{"code":200,"data":{"id":"s-1","kind":"GENERAL","projectId":null,"title":"新对话","lastMessagePreview":null,"createdAt":"2026-11-17T09:00:00Z","updatedAt":"2026-11-17T09:00:00Z","status":"ACTIVE"}}"#.utf8)
            }
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            return (response, body)
        }

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AgentGenerationURLProtocol.self]
        let testSession = AuthSession(token: "tok", refreshToken: nil, user: AuthUser(id: "u", email: "local", nickname: "B"), expiresAt: nil)
        let client = APIClient(keychain: .shared, session: URLSession(configuration: config), sessionProvider: { _ in testSession })
        let service = APIAgentService(client: client)

        let created = try await service.createSession(kind: .general, title: "新对话", projectId: nil)
        XCTAssertEqual(created.id, "s-1")
        XCTAssertEqual(seenPaths, ["GET /agent/reset/current", "POST /agent/sessions"])
        XCTAssertEqual(generationHeaders["/agent/sessions"] ?? nil, "7")
    }

    func testTimelineFormattingExtractsReadableParametersAndThinkingPreview() {
        let searchSummary = AgentTimelineBuilder.formatToolCommandSummary(
            #"{"max_results": 8, "query": "11月16日 科技股 收盘"}"#
        )
        XCTAssertEqual(searchSummary, "11月16日 科技股 收盘")

        let toolEquipSummary = AgentTimelineBuilder.formatToolCommandSummary(
            #"{"to_activate": ["web", "daily"]}"#
        )
        XCTAssertEqual(toolEquipSummary, "web, daily")

        let preview = AgentTimelineBuilder.thinkingPreview("用户接着聊科技股行情。\n我应该核实一下最新情况。")
        XCTAssertEqual(preview, "用户接着聊科技股行情。")
    }

    func testMarkdownParserExtractsTableBlockquoteThematicBreakAndHighlightedCode() {
        let markdown = """
        > 引用内容

        ```python
        def hello(name: str) -> str:
            return f"hi {name}"
        ```

        | 列 A | 列 B |
        |---|:---:|
        | 1 | 2 |

        ---
        结尾段落
        """

        let segments = MarkdownSegment.parse(markdown)
        XCTAssertEqual(segments.count, 5)
        XCTAssertEqual(segments[0].kind, .blockquote("引用内容"))
        XCTAssertEqual(
            segments[1].kind,
            .code(language: "python", code: "def hello(name: str) -> str:\n    return f\"hi {name}\"")
        )
        XCTAssertEqual(
            segments[2].kind,
            .table(headers: ["列 A", "列 B"], alignments: [.leading, .center], rows: [["1", "2"]])
        )
        XCTAssertEqual(segments[3].kind, .thematicBreak)
        XCTAssertEqual(segments[4].kind, .prose("结尾段落"))

        let highlighted = AgentCodeHighlighter.highlight("def hello():\n    return \"hi\"", language: "python", colorScheme: .light)
        let coloredRuns = highlighted.runs.filter { $0.foregroundColor != nil }
        XCTAssertGreaterThanOrEqual(coloredRuns.count, 3)
    }

    @MainActor
    func testPlanVisibilityFollowsSessionPlanUsageInsteadOfWorkspaceLeftover() async throws {
        let mock = MockAgentService()
        let viewModel = AgentChatViewModel(service: mock)
        await viewModel.sendPrompt("你好")
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertFalse(viewModel.currentSessionHasPlan)
        XCTAssertNil(viewModel.activePlanContent)
    }
}

private final class AgentGenerationURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let handler = Self.handler else {
            client?.urlProtocol(self, didFailWithError: URLError(.badServerResponse))
            return
        }
        do {
            let (response, data) = try handler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}

private actor MockAgentService: AgentServiceProtocol {
    var generatedTitleCalls = 0
    var submittedApprovalIds: [String] = []
    private var requirePermission = false
    private var savedMessages: [AgentTranscriptMessageDTO] = []

    func setRequirePermissionOnStream(_ value: Bool) {
        requirePermission = value
    }

    func listSessions() async throws -> [AgentSessionSummary] { [] }
    func listFeishuSessions() async throws -> [AgentSessionSummary] { [] }

    func getSessionDetail(sessionId: String) async throws -> AgentSessionDetailDTO {
        AgentSessionDetailDTO(
            summary: AgentSessionSummary(
                id: sessionId,
                kind: .general,
                projectId: nil,
                title: "新对话",
                lastMessagePreview: savedMessages.last?.content,
                createdAt: Date(),
                updatedAt: Date(),
                status: .active
            ),
            messages: savedMessages,
            usageSummary: nil
        )
    }

    func getFeishuSessionDetail(sessionId: String) async throws -> AgentSessionDetailDTO {
        try await getSessionDetail(sessionId: sessionId)
    }

    func createSession(kind: AgentSessionKind, title: String, projectId: String?) async throws -> AgentSessionSummary {
        AgentSessionSummary(
            id: "sess-created",
            kind: kind,
            projectId: projectId,
            title: title,
            lastMessagePreview: nil,
            createdAt: Date(),
            updatedAt: Date(),
            status: .active
        )
    }

    func updateSessionTitle(sessionId: String, title: String) async throws -> AgentSessionSummary {
        AgentSessionSummary(
            id: sessionId,
            kind: .general,
            projectId: nil,
            title: title,
            lastMessagePreview: nil,
            createdAt: Date(),
            updatedAt: Date(),
            status: .active
        )
    }

    func generateSessionTitle(sessionId: String) async throws -> AgentSessionSummary? {
        generatedTitleCalls += 1
        return AgentSessionSummary(
            id: sessionId,
            kind: .general,
            projectId: nil,
            title: "今日待办",
            lastMessagePreview: "这是来自服务器的回复",
            createdAt: Date(),
            updatedAt: Date(),
            status: .active
        )
    }

    func deleteSession(sessionId: String) async throws {}
    func getPermissionMode(sessionId: String) async throws -> AgentSessionPermissionMode { .ask }
    func updatePermissionMode(sessionId: String, mode: AgentSessionPermissionMode) async throws -> AgentSessionPermissionMode { mode }
    func fetchPendingPermission(sessionId: String) async throws -> AgentPendingApproval? { nil }

    func submitPermissionDecisions(
        sessionId: String,
        approvalId: String,
        decisions: [AgentPermissionDecisionInput]
    ) async throws -> AgentPermissionDecisionResponse {
        submittedApprovalIds.append(approvalId)
        return AgentPermissionDecisionResponse(readyToResume: true, nextTool: nil)
    }

    func cancelRun(sessionId: String, runId: String) async throws -> AgentRunCancelResponse {
        AgentRunCancelResponse(runId: runId, accepted: true, status: "CANCELLING")
    }

    func fetchPlan(sessionId: String) async throws -> String? { nil }

    nonisolated func streamChat(
        runId: String,
        sessionId: String,
        content: String,
        recordReferenceIds: [String]
    ) -> AsyncThrowingStream<AgentStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            Task {
                let needsApproval = await self.requirePermission
                await self.setMessages([
                    AgentTranscriptMessageDTO(
                        id: "u-1", turnId: "t-1", role: .user, content: content,
                        createdAt: Date(), status: .completed, durationMillis: nil,
                        tools: nil, thinking: nil, usage: nil, proactive: false, blocks: nil
                    ),
                    AgentTranscriptMessageDTO(
                        id: "a-1", turnId: "t-1", role: .assistant,
                        content: needsApproval ? "" : "这是来自服务器的回复",
                        createdAt: Date(), status: .completed, durationMillis: 1200,
                        tools: nil, thinking: nil, usage: nil, proactive: false, blocks: nil
                    )
                ])
                continuation.yield(.runStarted(runId: runId))
                if needsApproval {
                    continuation.yield(.permissionRequired(AgentPendingApproval(
                        sessionId: sessionId,
                        approvalId: "appr-1",
                        runId: runId,
                        turnId: "t-1",
                        partialContent: nil,
                        startedAt: Date(),
                        readyToResume: false,
                        tools: [
                            AgentPermissionTool(
                                toolCallId: "tc-edit",
                                toolName: "edit_file",
                                input: ["path": .string("README.md")],
                                riskDescription: "将修改本地文件内容",
                                index: 0,
                                total: 1
                            )
                        ]
                    )))
                } else {
                    continuation.yield(.text("这是来自服务器的回复"))
                    continuation.yield(.done)
                }
                continuation.finish()
            }
        }
    }

    nonisolated func resumeChat(
        sessionId: String,
        approvalId: String,
        runId: String
    ) -> AsyncThrowingStream<AgentStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            Task {
                await self.setMessages([
                    AgentTranscriptMessageDTO(
                        id: "a-1", turnId: "t-1", role: .assistant, content: "已恢复执行完成",
                        createdAt: Date(), status: .completed, durationMillis: 1500,
                        tools: nil, thinking: nil, usage: nil, proactive: false, blocks: nil
                    )
                ])
                continuation.yield(.text("已恢复执行完成"))
                continuation.yield(.done)
                continuation.finish()
            }
        }
    }

    private func setMessages(_ items: [AgentTranscriptMessageDTO]) {
        savedMessages = items
    }

    func fetchModelConfig() async throws -> AgentModelConfig {
        AgentModelConfig(
            vendor: "openai", name: "gpt-4o", apiKey: nil,
            temperature: 0.7, stream: true, baseUrl: nil, protocol: nil, supportsVision: true
        )
    }

    func fetchFullModelConfig() async throws -> AgentFullModelConfig {
        AgentFullModelConfig(
            activeVendor: "openai", activeModel: "gpt-4o", vendor: "openai",
            name: "gpt-4o", baseUrl: nil, protocol: nil, providers: []
        )
    }

    func switchModel(vendor: String, modelName: String, apiKey: String, baseUrl: String?, protocolName: String?) async throws {}
}
