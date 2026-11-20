import Foundation

/// 负责把 SSE 增量与后端 Transcript 消息映射为统一 ReAct 交错时间线模型。
enum AgentTimelineBuilder {
    static func mapTranscriptMessages(_ dtos: [AgentTranscriptMessageDTO]) -> [AgentChatMessage] {
        let userTurnIds = Set(dtos.compactMap { dto -> String? in
            guard dto.role == .user, let turnId = dto.turnId, !turnId.isEmpty else { return nil }
            return turnId
        })
        return dtos.map { mapTranscriptMessage($0, userTurnIds: userTurnIds) }
    }

    static func mapTranscriptMessage(
        _ dto: AgentTranscriptMessageDTO,
        userTurnIds: Set<String>? = nil
    ) -> AgentChatMessage {
        let isProactive = isProactiveTranscriptMessage(dto, userTurnIds: userTurnIds)
        let mappedTools = dto.tools ?? []
        let mappedBlocks: [AgentMessageBlock]
        if dto.role == .assistant {
            mappedBlocks = mapTranscriptBlocks(messageId: dto.id, dtos: dto.blocks)
        } else {
            mappedBlocks = []
        }

        let durationSeconds: Int?
        if let millis = dto.durationMillis {
            durationSeconds = max(1, Int((Double(millis) / 1000.0).rounded()))
        } else {
            durationSeconds = nil
        }

        return AgentChatMessage(
            id: dto.id,
            turnId: dto.turnId,
            role: dto.role,
            content: dto.content ?? "",
            thinking: (dto.thinking?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
                ? dto.thinking : nil,
            createdAt: dto.createdAt ?? Date(),
            status: dto.status,
            durationSeconds: durationSeconds,
            tools: mappedTools,
            blocks: mappedBlocks,
            proactive: isProactive,
            usage: dto.usage,
            isStreaming: false,
            replyImages: dto.role == .assistant ? (dto.replyImages ?? []) : []
        )
    }

    static func isProactiveTranscriptMessage(
        _ dto: AgentTranscriptMessageDTO,
        userTurnIds: Set<String>?
    ) -> Bool {
        guard dto.role == .assistant else { return false }
        if let explicit = dto.proactive { return explicit }
        guard let turnId = dto.turnId, !turnId.isEmpty, dto.status == .completed else { return false }
        guard (dto.durationMillis ?? 0) == 0, dto.usage == nil, dto.thinking == nil else { return false }
        guard (dto.tools ?? []).isEmpty, (dto.blocks ?? []).isEmpty else { return false }
        guard let userTurnIds else { return false }
        return !userTurnIds.contains(turnId)
    }

    static func appendText(_ message: AgentChatMessage, chunk: String) -> AgentChatMessage {
        guard !chunk.isEmpty else { return message }
        var updated = message
        updated.content += chunk
        updated.blocks = appendSequentialText(
            to: updated.blocks,
            messageId: updated.id,
            kind: .text,
            chunk: chunk
        )
        return updated
    }

    static func appendThinking(_ message: AgentChatMessage, chunk: String) -> AgentChatMessage {
        guard !chunk.isEmpty else { return message }
        var updated = message
        updated.thinking = (updated.thinking ?? "") + chunk
        updated.blocks = appendSequentialText(
            to: updated.blocks,
            messageId: updated.id,
            kind: .thinking,
            chunk: chunk
        )
        return updated
    }

    static func upsertToolCall(
        _ message: AgentChatMessage,
        toolCallId: String,
        toolName: String,
        command: String
    ) -> AgentChatMessage {
        var updated = message
        let resolvedId = toolCallId.isEmpty ? "tool_\(UUID().uuidString)" : toolCallId
        let resolvedName = toolName.isEmpty ? "tool" : toolName

        updated.tools = upsertTool(
            in: updated.tools,
            toolCallId: resolvedId,
            toolName: resolvedName,
            command: command
        )

        if let existingIndex = updated.blocks.lastIndex(where: {
            $0.kind == .toolGroup && $0.tools.contains(where: { $0.toolCallId == resolvedId })
        }) {
            updated.blocks[existingIndex].tools = upsertTool(
                in: updated.blocks[existingIndex].tools,
                toolCallId: resolvedId,
                toolName: resolvedName,
                command: command
            )
            return updated
        }

        if var last = updated.blocks.last, last.kind == .toolGroup {
            last.tools = upsertTool(
                in: last.tools,
                toolCallId: resolvedId,
                toolName: resolvedName,
                command: command
            )
            updated.blocks[updated.blocks.count - 1] = last
        } else {
            let tool = AgentToolExecution(
                toolCallId: resolvedId,
                toolName: resolvedName,
                command: command,
                output: nil,
                status: .running
            )
            updated.blocks.append(AgentMessageBlock(
                id: "\(updated.id)_b_\(updated.blocks.count)",
                kind: .toolGroup,
                content: "",
                tools: [tool]
            ))
        }
        return updated
    }

    static func applyToolResult(
        _ message: AgentChatMessage,
        toolCallId: String,
        result: String,
        status: AgentToolStatus
    ) -> AgentChatMessage {
        var updated = message
        updated.tools = applyResult(in: updated.tools, toolCallId: toolCallId, result: result, status: status).tools

        for index in updated.blocks.indices.reversed() where updated.blocks[index].kind == .toolGroup {
            let applied = applyResult(
                in: updated.blocks[index].tools,
                toolCallId: toolCallId,
                result: result,
                status: status
            )
            if applied.updated {
                updated.blocks[index].tools = applied.tools
                break
            }
        }
        return updated
    }

    /// 完成后把前置思考、过程正文与工具调用分离，最后一个 text 块作为最终回答直排展示。
    static func splitProcessAndFinalBlocks(
        for message: AgentChatMessage
    ) -> (processBlocks: [AgentMessageBlock], finalText: String, hasProcessTrace: Bool) {
        let blocks = normalizedBlocks(for: message)
        guard !blocks.isEmpty else {
            return ([], message.content, false)
        }
        let hasProcess = blocks.contains { $0.kind == .thinking || $0.kind == .toolGroup }
        if !hasProcess {
            let combined = blocks.filter { $0.kind == .text }.map(\.content).joined(separator: "\n\n")
            return ([], combined.isEmpty ? message.content : combined, false)
        }
        if let last = blocks.last, last.kind == .text,
           !last.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let prefix = Array(blocks.dropLast())
            return (prefix, last.content, !prefix.isEmpty)
        }
        return (blocks, "", true)
    }

    /// 按阶段进展正文分段聚合展示时间线：
    /// 两段正文之间连续的多轮思考与工具调用合并为单个阶段活动条，避免手机端堆满零散的思考与工具行；
    /// 仅含思考而尚未出现工具的片段保留独立思考块，不修改原始 blocks。
    static func groupTimelineBlocks(_ blocks: [AgentMessageBlock]) -> [AgentDisplayTimelineBlock] {
        var result: [AgentDisplayTimelineBlock] = []
        var pendingId = ""
        var pendingTools: [AgentToolExecution] = []
        var pendingThinking: [String] = []
        var lastNonTextKind: AgentMessageBlockKind?

        func flushPending() {
            if !pendingTools.isEmpty {
                result.append(AgentDisplayTimelineBlock(
                    id: pendingId,
                    kind: .toolGroup,
                    content: "",
                    tools: pendingTools,
                    analyzingAfterTools: lastNonTextKind == .thinking
                ))
            } else if !pendingThinking.isEmpty {
                result.append(AgentDisplayTimelineBlock(
                    id: pendingId,
                    kind: .thinking,
                    content: pendingThinking.joined(separator: "\n"),
                    tools: [],
                    analyzingAfterTools: false
                ))
            }
            pendingId = ""
            pendingTools = []
            pendingThinking = []
            lastNonTextKind = nil
        }

        for block in blocks {
            switch block.kind {
            case .text:
                if block.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    continue
                }
                flushPending()
                result.append(AgentDisplayTimelineBlock(
                    id: block.id,
                    kind: .text,
                    content: block.content,
                    tools: [],
                    analyzingAfterTools: false
                ))
            case .toolGroup:
                guard !block.tools.isEmpty else { continue }
                if pendingId.isEmpty { pendingId = block.id }
                pendingTools.append(contentsOf: block.tools)
                lastNonTextKind = .toolGroup
            case .thinking:
                let trimmed = block.content.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !trimmed.isEmpty else { continue }
                if pendingId.isEmpty { pendingId = block.id }
                pendingThinking.append(trimmed)
                lastNonTextKind = .thinking
            }
        }
        flushPending()
        return result
    }

    /// 统一构建消息块；历史消息无 `blocks` 时兼容回退为思考 + 工具组 + 正文。
    static func normalizedBlocks(for message: AgentChatMessage) -> [AgentMessageBlock] {
        if !message.blocks.isEmpty {
            return message.blocks
        }
        var fallback: [AgentMessageBlock] = []
        if let thinking = message.thinking, !thinking.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            fallback.append(AgentMessageBlock(
                id: "\(message.id)_fb_thinking",
                kind: .thinking,
                content: thinking,
                tools: []
            ))
        }
        if !message.tools.isEmpty {
            fallback.append(AgentMessageBlock(
                id: "\(message.id)_fb_tools",
                kind: .toolGroup,
                content: "",
                tools: message.tools
            ))
        }
        if !message.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            fallback.append(AgentMessageBlock(
                id: "\(message.id)_fb_text",
                kind: .text,
                content: message.content,
                tools: []
            ))
        }
        return fallback
    }

    private static func mapTranscriptBlocks(
        messageId: String,
        dtos: [AgentTranscriptBlockDTO]?
    ) -> [AgentMessageBlock] {
        guard let dtos, !dtos.isEmpty else { return [] }
        var result: [AgentMessageBlock] = []
        for (index, block) in dtos.enumerated() {
            switch block.kind {
            case .thinking:
                if let text = block.content, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    result.append(AgentMessageBlock(
                        id: "\(messageId)_b_\(index)",
                        kind: .thinking,
                        content: text,
                        tools: []
                    ))
                }
            case .text:
                if let text = block.content, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    result.append(AgentMessageBlock(
                        id: "\(messageId)_b_\(index)",
                        kind: .text,
                        content: text,
                        tools: []
                    ))
                }
            case .toolGroup:
                if let tools = block.tools, !tools.isEmpty {
                    result.append(AgentMessageBlock(
                        id: "\(messageId)_b_\(index)",
                        kind: .toolGroup,
                        content: "",
                        tools: tools
                    ))
                }
            }
        }
        return result
    }

    private static func appendSequentialText(
        to blocks: [AgentMessageBlock],
        messageId: String,
        kind: AgentMessageBlockKind,
        chunk: String
    ) -> [AgentMessageBlock] {
        var next = blocks
        if var last = next.last, last.kind == kind {
            last.content += chunk
            next[next.count - 1] = last
            return next
        }
        if chunk.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return blocks
        }
        next.append(AgentMessageBlock(
            id: "\(messageId)_b_\(next.count)",
            kind: kind,
            content: chunk,
            tools: []
        ))
        return next
    }

    private static func upsertTool(
        in tools: [AgentToolExecution],
        toolCallId: String,
        toolName: String,
        command: String
    ) -> [AgentToolExecution] {
        var next = tools
        if let index = next.firstIndex(where: { $0.toolCallId == toolCallId }) {
            if !toolName.isEmpty { next[index].toolName = toolName }
            if !command.isEmpty { next[index].command = command }
            return next
        }
        next.append(AgentToolExecution(
            toolCallId: toolCallId,
            toolName: toolName,
            command: command,
            output: nil,
            status: .running
        ))
        return next
    }

    private static func applyResult(
        in tools: [AgentToolExecution],
        toolCallId: String,
        result: String,
        status: AgentToolStatus
    ) -> (updated: Bool, tools: [AgentToolExecution]) {
        var next = tools
        let targetIndex: Int?
        if !toolCallId.isEmpty, let matched = next.firstIndex(where: { $0.toolCallId == toolCallId }) {
            targetIndex = matched
        } else {
            targetIndex = next.lastIndex(where: { $0.status == .running })
        }
        guard let index = targetIndex else {
            return (false, tools)
        }
        if !result.isEmpty {
            next[index].output = (next[index].output ?? "") + result
        }
        next[index].status = status
        return (true, next)
    }

    /// 提取思考块折叠态的首句预览，避免手机端一展开就被整屏原文淹没。
    static func thinkingPreview(_ raw: String) -> String {
        let singleLine = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        guard !singleLine.isEmpty else { return "" }
        if let end = singleLine.firstIndex(where: { "。！？!?；;\n".contains($0) }) {
            let sentence = String(singleLine[...end]).trimmingCharacters(in: .whitespaces)
            if sentence.count <= 60 { return sentence }
        }
        if singleLine.count > 48 {
            return String(singleLine.prefix(48)) + "…"
        }
        return singleLine
    }

    /// 将工具原始 JSON 参数提炼为可读摘要（如提取搜索词、路径、激活能力组）。
    static func formatToolCommandSummary(_ rawCommand: String) -> String {
        let trimmed = rawCommand.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        guard trimmed.hasPrefix("{"),
              let data = trimmed.data(using: .utf8),
              let json = try? JSONDecoder().decode([String: JSONValue].self, from: data)
        else {
            return trimmed
        }

        let priorityKeys = [
            "query", "queries", "to_activate", "activate", "groups",
            "path", "file_path", "target_file", "command", "cmd",
            "url", "sql", "title", "topic", "keyword", "content"
        ]
        for key in priorityKeys {
            if let value = json[key], let formatted = readableJSONParameter(value) {
                return formatted
            }
        }
        for key in json.keys.sorted() {
            if let value = json[key], case .string = value,
               let formatted = readableJSONParameter(value) {
                return formatted
            }
        }
        for key in json.keys.sorted() {
            if let value = json[key], case .array = value,
               let formatted = readableJSONParameter(value) {
                return formatted
            }
        }
        return ""
    }

    private static func readableJSONParameter(_ value: JSONValue) -> String? {
        switch value {
        case .string(let text):
            let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return clean.isEmpty ? nil : clean
        case .array(let items):
            let parts = items.compactMap { readableJSONParameter($0) }
            return parts.isEmpty ? nil : parts.joined(separator: ", ")
        case .number, .bool, .null, .object:
            return nil
        }
    }
}
