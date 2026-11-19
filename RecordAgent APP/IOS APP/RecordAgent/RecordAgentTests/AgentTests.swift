import SwiftUI
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

    func testAssistantReplyImageSurvivesHistoryDecoding() throws {
        let json = """
        {"id":"reply","role":"ASSISTANT","replyImages":[
          {"fileId":"image-id","title":"参考图","sourceUrl":"https://example.com/photo.png"}
        ]}
        """
        let dto = try JSONDecoder().decode(AgentTranscriptMessageDTO.self, from: Data(json.utf8))
        let reply = AgentTimelineBuilder.mapTranscriptMessage(dto)
        XCTAssertEqual(reply.replyImages.first?.id, "image-id")
        XCTAssertEqual(reply.replyImages.first?.source?.host, "example.com")
        XCTAssertNil(AgentReplyImage(fileId: "unsafe", title: "", sourceUrl: "file:///tmp/secret").source)
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

    func testReplyImageUsesBearerHeaderAndCompactFileIdentifier() async throws {
        let previousAddress = APIConfiguration.savedServerAddress()
        addTeardownBlock {
            if previousAddress.isEmpty {
                APIConfiguration.clearServerAddress()
            } else {
                _ = try? APIConfiguration.saveServerAddress(previousAddress)
            }
            AgentGenerationURLProtocol.handler = nil
        }
        _ = try APIConfiguration.saveServerAddress("http://127.0.0.1:18081")
        let fileId = "a6e04bb4f2664f1196500e850c2fcd5a"
        let pngHeader = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0])
        AgentGenerationURLProtocol.handler = { request in
            let url = try XCTUnwrap(request.url)
            XCTAssertEqual(url.path, "/agent/materials/\(fileId)/content")
            XCTAssertNil(url.query)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
            let response = try XCTUnwrap(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil,
                                                        headerFields: ["Content-Type": "image/png"]))
            return (response, pngHeader)
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AgentGenerationURLProtocol.self]
        let credentials = AuthSession(token: "test-token", refreshToken: nil,
            user: AuthUser(id: "u", email: "local", nickname: "B"), expiresAt: nil)
        let client = APIClient(keychain: .shared, session: URLSession(configuration: config), sessionProvider: { _ in credentials })
        let image = try await APIAgentService(client: client).fetchReplyImage(fileId: fileId)
        XCTAssertEqual(image, pngHeader)
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
        XCTAssertEqual(segments[4].kind, .paragraph("结尾段落"))

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

    func testInlineMarkdownParserSupportsBoldAndSemanticColors() {
        let source = "支出 **<font color=\"red\">-¥96.00</font>**，收入 <font color='green'>**+¥5,000.00**</font>，结余 <span style=\"color: #2563EB\">¥4,904.00</span>。"
        let runs = AgentInlineMarkdownParser.parseRuns(source)

        XCTAssertEqual(runs.map(\.text).joined(), "支出 -¥96.00，收入 +¥5,000.00，结余 ¥4,904.00。")

        let expenseRun = runs.first { $0.text == "-¥96.00" }
        XCTAssertNotNil(expenseRun)
        XCTAssertEqual(expenseRun?.isBold, true)
        XCTAssertEqual(expenseRun?.colorToken, "red")

        let incomeRun = runs.first { $0.text == "+¥5,000.00" }
        XCTAssertNotNil(incomeRun)
        XCTAssertEqual(incomeRun?.isBold, true)
        XCTAssertEqual(incomeRun?.colorToken, "green")

        let balanceRun = runs.first { $0.text == "¥4,904.00" }
        XCTAssertNotNil(balanceRun)
        XCTAssertEqual(balanceRun?.isBold, false)
        XCTAssertEqual(balanceRun?.colorToken, "#2563EB")
    }

    func testInlineMarkdownParserHandlesChinesePunctuationCodeAndStreamingTags() {
        let chineseBold = "**11/12 小菜园**：花费 96 元；`foo_bar` 与 `<font color=\"red\">原样</font>` 不转义。"
        let runs = AgentInlineMarkdownParser.parseRuns(chineseBold)
        XCTAssertEqual(runs.first?.text, "11/12 小菜园")
        XCTAssertEqual(runs.first?.isBold, true)
        XCTAssertTrue(runs.contains { $0.isCode && $0.text == "<font color=\"red\">原样</font>" && $0.colorToken == nil })

        // 流式截断：未闭合开始标签继续着色；半截尖括号暂隐防止源码闪烁
        let streamingUnclosed = AgentInlineMarkdownParser.parseRuns("餐饮 <font color=\"red\">-96")
        XCTAssertEqual(streamingUnclosed.map(\.text).joined(), "餐饮 -96")
        XCTAssertEqual(streamingUnclosed.last?.colorToken, "red")

        let streamingHalfOpen = AgentInlineMarkdownParser.parseRuns("合计 <font color=\"re")
        XCTAssertEqual(streamingHalfOpen.map(\.text).joined(), "合计 ")

        XCTAssertEqual(
            AgentInlineMarkdownParser.stripMarkupTagsOnly("支出 **<font color=\"red\">-¥96.00</font>**"),
            "支出 **-¥96.00**"
        )
        XCTAssertNotNil(AgentInlineMarkdownParser.resolveColor("expense", colorScheme: .light))
        XCTAssertNotNil(AgentInlineMarkdownParser.resolveColor("income", colorScheme: .dark))
        XCTAssertNotNil(AgentInlineMarkdownParser.resolveColor("#FF6E5A", colorScheme: .dark))
    }

    func testMarkdownSegmentParsesHeadingsListsTablesAndParagraphs() {
        let markdown = """
        ## 本月支出统计

        | 分类 | 金额 | 占比 |
        | :--- | ---: | ---: |
        | 餐饮 | <font color="red">-227.00</font> | 42.1% |
        | 基金定投 | <font color="green">+100.00</font> | 18.5% |

        第一段第一行
        第一段第二行

        - **餐饮**：主要花费
        - 奶茶：合计 45.10 元
        """

        let segments = MarkdownSegment.parse(markdown)
        XCTAssertEqual(segments.count, 4)

        guard case .heading(let level, let title) = segments[0].kind else {
            return XCTFail("首段应为二级标题")
        }
        XCTAssertEqual(level, 2)
        XCTAssertEqual(title, "本月支出统计")

        guard case .table(let headers, let alignments, let rows) = segments[1].kind else {
            return XCTFail("第二段应为表格")
        }
        XCTAssertEqual(headers, ["分类", "金额", "占比"])
        XCTAssertEqual(alignments, [.leading, .trailing, .trailing])
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0][1], "<font color=\"red\">-227.00</font>")

        guard case .paragraph(let para) = segments[2].kind else {
            return XCTFail("第三段应合并为单个自然段落")
        }
        XCTAssertEqual(para, "第一段第一行\n第一段第二行")

        guard case .list(let items) = segments[3].kind else {
            return XCTFail("第四段应为列表")
        }
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(items[0].text, "**餐饮**：主要花费")
    }

    func testSessionTimeGroupingSeparatesTodayYesterdayLast7DaysAndOlder() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        let now = calendar.date(from: DateComponents(year: 2026, month: 11, day: 17, hour: 16, minute: 0))!
        let todayDate = calendar.date(byAdding: .hour, value: -2, to: now)!
        let yesterdayDate = calendar.date(byAdding: .day, value: -1, to: now)!
        let weekDate = calendar.date(byAdding: .day, value: -4, to: now)!
        let olderDate = calendar.date(byAdding: .day, value: -12, to: now)!

        func makeSession(id: String, date: Date) -> AgentSessionSummary {
            AgentSessionSummary(
                id: id,
                kind: .general,
                projectId: nil,
                title: id,
                lastMessagePreview: nil,
                createdAt: date,
                updatedAt: date,
                status: .active
            )
        }

        let grouped = AgentSessionTimeGroup.group(
            [
                makeSession(id: "s-today", date: todayDate),
                makeSession(id: "s-yesterday", date: yesterdayDate),
                makeSession(id: "s-week", date: weekDate),
                makeSession(id: "s-older", date: olderDate)
            ],
            now: now,
            calendar: calendar
        )

        XCTAssertEqual(grouped.map(\.group), [.today, .yesterday, .last7Days, .older])
        XCTAssertEqual(grouped[0].items.map(\.id), ["s-today"])
        XCTAssertEqual(grouped[1].items.map(\.id), ["s-yesterday"])
        XCTAssertEqual(grouped[2].items.map(\.id), ["s-week"])
        XCTAssertEqual(grouped[3].items.map(\.id), ["s-older"])
    }

    @MainActor
    func testAgentDrawerContainerRestoresSafeAreaInsetsAndFullScreenCardBounds() {
        var measuredMainFrame: CGRect = .zero
        var measuredMainInsets: EdgeInsets = .init()
        var measuredDrawerFrame: CGRect = .zero

        let host = UIHostingController(
            rootView: AgentDrawerContainer(
                isEnabled: true,
                isOpen: .constant(true)
            ) {
                GeometryReader { p in
                    Color.clear
                        .onAppear {
                            measuredDrawerFrame = p.frame(in: .global)
                        }
                }
            } main: {
                GeometryReader { p in
                    Color.clear
                        .onAppear {
                            measuredMainFrame = p.frame(in: .global)
                            measuredMainInsets = p.safeAreaInsets
                        }
                }
            }
        )

        host.additionalSafeAreaInsets = UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()

        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        let expectedInsets = host.view.safeAreaInsets

        // main 内容位于安全区内，不因外层 ignoresSafeArea 丢失也不重复累加安全区
        XCTAssertEqual(measuredMainFrame.minY, expectedInsets.top, accuracy: 1.0)
        XCTAssertEqual(
            measuredMainFrame.height,
            852 - expectedInsets.top - expectedInsets.bottom,
            accuracy: 1.0
        )
        XCTAssertEqual(measuredMainInsets.top, 0, accuracy: 1.0)
        XCTAssertEqual(measuredDrawerFrame.minY, expectedInsets.top, accuracy: 1.0)
    }

    func testTurnTokenUsageDecodesFullBreakdownAndAggregatesSession() throws {
        let json = """
        {
          "summary": {
            "id": "s-usage",
            "kind": "GENERAL",
            "projectId": null,
            "title": "Token 测试",
            "lastMessagePreview": "完成",
            "createdAt": "2026-11-17T08:00:00Z",
            "updatedAt": "2026-11-17T08:01:00Z",
            "status": "ACTIVE"
          },
          "messages": [
            {
              "id": "u-1",
              "turnId": "t-1",
              "role": "USER",
              "content": "分析一下文档"
            },
            {
              "id": "a-1",
              "turnId": "t-1",
              "role": "ASSISTANT",
              "content": "已分析",
              "status": "COMPLETED",
              "durationMillis": 1400,
              "usage": {
                "inputTokens": 1200,
                "outputTokens": 300,
                "cachedInputTokens": 200,
                "totalTokens": 1500,
                "modelCallCount": 2,
                "reportedCallCount": 2,
                "status": "COMPLETE",
                "estimatedInputTokens": 1150,
                "estimationDeltaTokens": 50,
                "durationMillis": 1350,
                "breakdown": {
                  "systemPromptTokens": 400,
                  "historyTokens": 150,
                  "currentUserTokens": 100,
                  "toolSchemaTokens": 250,
                  "toolResultTokens": 100,
                  "profileContextTokens": 50,
                  "memoryRecallTokens": 50,
                  "ragContextTokens": 50,
                  "otherTokens": 50
                },
                "toolUsages": [
                  { "toolName": "read_file", "schemaTokens": 250, "resultTokens": 100 }
                ],
                "calls": [
                  {
                    "invocationId": "inv-1",
                    "modelCallIndex": 1,
                    "source": "chat",
                    "purpose": "CHAT",
                    "vendor": "openai",
                    "model": "gpt-4o",
                    "inputTokens": 1200,
                    "outputTokens": 300,
                    "cachedInputTokens": 200,
                    "totalTokens": 1500,
                    "durationMillis": 1350,
                    "status": "COMPLETE"
                  }
                ]
              }
            }
          ],
          "usageSummary": {
            "inputTokens": 1200,
            "outputTokens": 300,
            "cachedInputTokens": 200,
            "totalTokens": 1500,
            "turnCount": 1,
            "trackedTurnCount": 1,
            "modelCallCount": 2,
            "reportedCallCount": 2,
            "status": "COMPLETE"
          }
        }
        """

        let detail = try APIClient.decoder.decode(AgentSessionDetailDTO.self, from: Data(json.utf8))
        let messages = AgentTimelineBuilder.mapTranscriptMessages(detail.messages)
        let turns = AgentTokenUsageAnalyzer.turnOptions(from: messages)
        let aggregated = AgentTokenUsageAnalyzer.aggregateSessionUsage(turns: turns, summary: detail.usageSummary)

        XCTAssertEqual(turns.count, 1)
        XCTAssertEqual(aggregated?.totalTokens, 1500)
        XCTAssertEqual(aggregated?.breakdown.systemPromptTokens, 400)
        XCTAssertEqual(aggregated?.breakdown.ragContextTokens, 50)
        XCTAssertEqual(aggregated?.toolUsages.first?.toolName, "read_file")
        XCTAssertEqual(aggregated?.calls.count, 1)
        XCTAssertEqual(AgentTokenUsageAnalyzer.formatCompactTokenCount(1500), "1.5k")
        XCTAssertEqual(AgentTokenUsageAnalyzer.formatTokenCount(1500), "1,500")
        XCTAssertEqual(AgentTokenUsageAnalyzer.formatDuration(1350), "1.4 s")

        let segments = AgentTokenUsageAnalyzer.breakdownSegments(from: aggregated!.breakdown)
        XCTAssertEqual(segments.count, 9)
        XCTAssertEqual(segments.first?.key, .systemPrompt)
        XCTAssertFalse(segments.first?.key.symbolName.isEmpty ?? true)
        XCTAssertEqual(aggregated?.inputShare ?? 0, 0.8, accuracy: 0.001)
        XCTAssertEqual(aggregated?.outputShare ?? 0, 0.2, accuracy: 0.001)
        XCTAssertEqual(aggregated?.cacheHitRatio ?? 0, 200.0 / 1200.0, accuracy: 0.001)
    }

    func testImageValidatorEnforcesMagicBytesAndLimits() throws {
        let validPNG = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])
        let validJPEG = Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01])
        let invalid = Data("not-an-image-12345".utf8)

        XCTAssertThrowsError(try AgentImageValidator.makeAttachment(from: Data(), existing: [])) { error in
            XCTAssertEqual(error as? AgentImageValidationError, .empty)
        }
        XCTAssertThrowsError(try AgentImageValidator.makeAttachment(from: invalid, existing: [])) { error in
            XCTAssertEqual(error as? AgentImageValidationError, .unsupportedFormat)
        }

        let first = try AgentImageValidator.makeAttachment(from: validPNG, existing: [])
        XCTAssertEqual(first.mediaType, "image/png")
        let second = try AgentImageValidator.makeAttachment(from: validJPEG, existing: [first])
        XCTAssertEqual(second.mediaType, "image/jpeg")

        let four = [first, second, first, second]
        XCTAssertThrowsError(try AgentImageValidator.makeAttachment(from: validPNG, existing: four)) { error in
            XCTAssertEqual(error as? AgentImageValidationError, .tooManyImages)
        }
    }

    @MainActor
    func testVisionValidationBlocksUnsupportedModelAndPreservesDraft() async throws {
        let mock = MockAgentService()
        await mock.setSupportsVision(false)
        let viewModel = AgentChatViewModel(service: mock)
        await viewModel.refreshModelState()

        let validPNG = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])
        let token = viewModel.beginImageProcessing()
        viewModel.appendValidatedImageData([validPNG], draftToken: token)
        viewModel.finishImageProcessing(token: token)
        viewModel.draftText = "看下这张图"

        await viewModel.sendCurrentDraft()
        XCTAssertNotNil(viewModel.errorMessage)
        XCTAssertEqual(viewModel.draftText, "看下这张图")
        XCTAssertEqual(viewModel.attachedImages.count, 1)
        let streamCountBefore = await mock.streamRequests.count
        XCTAssertEqual(streamCountBefore, 0)

        // 切换为支持视觉的模型后，允许仅发图片并清空草稿
        await mock.setSupportsVision(true)
        await viewModel.refreshModelState()
        viewModel.draftText = ""
        await viewModel.sendCurrentDraft()
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertTrue(viewModel.attachedImages.isEmpty)
        XCTAssertEqual(viewModel.messages.first(where: { $0.role == .user })?.images.count, 1)
        let requests = await mock.streamRequests
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests.first?.images.count, 1)
        XCTAssertEqual(requests.first?.content, "")
    }

    @MainActor
    func testRecordReferencePickerAndSendPassesIdsAndContext() async throws {
        let mock = MockAgentService()
        let viewModel = AgentChatViewModel(service: mock)

        viewModel.openRecordPicker()
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertTrue(viewModel.isRecordPickerOpen)
        XCTAssertEqual(viewModel.recordReferenceOptions.count, 1)

        viewModel.toggleRecordReference(viewModel.recordReferenceOptions[0])
        XCTAssertFalse(viewModel.isRecordPickerOpen)
        XCTAssertEqual(viewModel.selectedRecordReferences.count, 1)
        // 引用资料时必须输入问题正文才允许发送
        XCTAssertFalse(viewModel.canSend)

        viewModel.draftText = "总结这份笔记"
        XCTAssertTrue(viewModel.canSend)

        await viewModel.sendCurrentDraft()
        try await Task.sleep(for: .milliseconds(100))

        XCTAssertTrue(viewModel.selectedRecordReferences.isEmpty)
        let sent = await mock.streamRequests.last
        XCTAssertEqual(sent?.recordReferenceIds, ["rec-1"])
        XCTAssertEqual(sent?.context, "总结这份笔记")
        XCTAssertEqual(sent?.content, "[资料：算法刷题笔记] 总结这份笔记")
    }

    @MainActor
    func testSwitchingSessionClearsDraftAttachmentsAndDiscardsStaleImageLoads() async throws {
        let mock = MockAgentService()
        let viewModel = AgentChatViewModel(service: mock)
        let validPNG = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D])

        let staleToken = viewModel.beginImageProcessing()
        viewModel.startNewChat()

        // 切换会话前的异步读图结果应被丢弃
        viewModel.appendValidatedImageData([validPNG], draftToken: staleToken)
        viewModel.finishImageProcessing(token: staleToken)

        XCTAssertTrue(viewModel.attachedImages.isEmpty)
        XCTAssertFalse(viewModel.isProcessingImages)
    }

    func testReferenceCitationParserExtractsUserTagsAndAssistantSnippets() {
        let userMsg = AgentChatMessage(
            id: "u-1",
            turnId: "t-1",
            role: .user,
            content: "[资料：算法刷题笔记、复盘清单] 帮我总结核心要点",
            thinking: nil,
            createdAt: Date(),
            status: .completed,
            durationSeconds: nil,
            tools: [],
            blocks: [],
            proactive: false,
            usage: nil,
            isStreaming: false
        )
        let parsedUser = AgentReferenceCitationParser.parseUserPromptAndReferences(userMsg.content)
        XCTAssertEqual(parsedUser.referenceTitles, ["算法刷题笔记", "复盘清单"])
        XCTAssertEqual(parsedUser.prompt, "帮我总结核心要点")

        let assistantText = """
        核心要点如下 [1][2]。

        ## 引用资料
        [1] 《算法刷题笔记》：“层序遍历使用队列按层推进”
        [2] 复盘清单：每周日晚回顾错题
        """
        let assistantMsg = AgentChatMessage(
            id: "a-1",
            turnId: "t-1",
            role: .assistant,
            content: assistantText,
            thinking: nil,
            createdAt: Date(),
            status: .completed,
            durationSeconds: 2,
            tools: [],
            blocks: [],
            proactive: false,
            usage: nil,
            isStreaming: false
        )
        let cachedOption = AgentRecordReferenceOption(
            id: "rec-1",
            recordDate: "2026-11-17",
            type: .learning,
            title: "算法刷题笔记",
            summary: "摘要",
            tags: [],
            updatedAt: nil
        )
        let citations = AgentReferenceCitationParser.citedReferences(
            forAssistantIndex: 1,
            in: [userMsg, assistantMsg],
            finalText: assistantText,
            cachedMetadata: ["算法刷题笔记": cachedOption]
        )

        XCTAssertEqual(citations.count, 2)
        XCTAssertEqual(citations[0].recordId, "rec-1")
        XCTAssertEqual(citations[0].type, .learning)
        XCTAssertEqual(citations[0].snippet, "层序遍历使用队列按层推进")
        XCTAssertEqual(citations[1].title, "复盘清单")
        XCTAssertEqual(citations[1].snippet, "每周日晚回顾错题")
    }

    func testLatestContextMetricsCalculatesWindowOccupancyAndTimestampSeconds() {
        let usage = AgentTurnTokenUsage(
            inputTokens: 6400,
            outputTokens: 600,
            cachedInputTokens: 0,
            totalTokens: 7000,
            calls: [
                AgentModelInvocationUsage(
                    invocationId: "i-1",
                    modelCallIndex: 1,
                    source: "chat",
                    purpose: .chat,
                    vendor: "deepseek",
                    model: "deepseek-v4.1-flash-1017",
                    inputTokens: 6400,
                    outputTokens: 600,
                    cachedInputTokens: 0,
                    totalTokens: 7000,
                    durationMillis: 1200,
                    status: .complete,
                    tokenCounterId: nil,
                    estimatedInputTokens: 6200,
                    estimationDeltaTokens: 200,
                    breakdown: nil,
                    toolUsages: nil
                )
            ]
        )
        let assistantMsg = AgentChatMessage(
            id: "a-1",
            turnId: "t-1",
            role: .assistant,
            content: "回答",
            thinking: nil,
            createdAt: Date(timeIntervalSince1970: 1_763_379_349),
            status: .completed,
            durationSeconds: 1,
            tools: [],
            blocks: [],
            proactive: false,
            usage: usage,
            isStreaming: false
        )

        let metrics = AgentTokenUsageAnalyzer.latestContextMetrics(
            from: [assistantMsg],
            modelName: "deepseek-v4.1-flash-1017",
            vendor: "deepseek",
            configuredLimit: nil
        )
        XCTAssertEqual(metrics.currentContextTokens, 6400)
        XCTAssertEqual(metrics.contextWindowLimit, 128_000)
        XCTAssertEqual(metrics.occupancyPercentText, "5%")
        XCTAssertTrue(metrics.isLimitEstimated)

        let timestamp = AgentTokenUsageAnalyzer.formatTimestampSeconds(assistantMsg.createdAt)
        XCTAssertEqual(timestamp.count, 19)
        XCTAssertTrue(timestamp.contains(":"))
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
    struct CapturedStreamRequest: Sendable {
        let content: String
        let context: String
        let recordReferenceIds: [String]
        let images: [AgentChatImagePayload]
    }

    var generatedTitleCalls = 0
    var submittedApprovalIds: [String] = []
    var streamRequests: [CapturedStreamRequest] = []
    private var requirePermission = false
    private var visionSupported = true
    private var savedMessages: [AgentTranscriptMessageDTO] = []

    func setRequirePermissionOnStream(_ value: Bool) {
        requirePermission = value
    }

    func setSupportsVision(_ value: Bool) {
        visionSupported = value
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

    func fetchRecordReferences(query: String, limit: Int, offset: Int) async throws -> AgentRecordReferencePage {
        let item = AgentRecordReferenceOption(
            id: "rec-1",
            recordDate: "2026-11-17",
            type: .learning,
            title: "算法刷题笔记",
            summary: "二叉树层序遍历与双指针模板",
            tags: ["算法"],
            updatedAt: Date()
        )
        let items = query.isEmpty || item.displayTitle.localizedCaseInsensitiveContains(query) ? [item] : []
        return AgentRecordReferencePage(items: items, hasMore: false, nextOffset: items.count)
    }

    func fetchRecordDetail(id: String) async throws -> AgentRecordDetail {
        AgentRecordDetail(
            id: id,
            recordDate: "2026-11-17",
            type: .learning,
            title: "算法刷题笔记",
            contentText: "二叉树层序遍历与双指针模板正文",
            tags: ["算法"],
            updatedAt: Date()
        )
    }

    nonisolated func streamChat(
        runId: String,
        sessionId: String,
        content: String,
        context: String,
        recordReferenceIds: [String],
        images: [AgentChatImagePayload]
    ) -> AsyncThrowingStream<AgentStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            Task {
                await self.recordStreamRequest(CapturedStreamRequest(
                    content: content,
                    context: context,
                    recordReferenceIds: recordReferenceIds,
                    images: images
                ))
                let needsApproval = await self.requirePermission
                await self.setMessages([
                    AgentTranscriptMessageDTO(
                        id: "u-1", turnId: "t-1", role: .user,
                        content: content.isEmpty && !images.isEmpty ? "用户发送了一张图片，请识别并回应图片内容。" : content,
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

    private func recordStreamRequest(_ request: CapturedStreamRequest) {
        streamRequests.append(request)
    }

    func fetchModelConfig() async throws -> AgentModelConfig {
        AgentModelConfig(
            vendor: "openai", name: "gpt-4o", apiKey: nil,
            temperature: 0.7, stream: true, baseUrl: nil, protocol: nil, supportsVision: visionSupported
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
