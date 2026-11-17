import Foundation

/// 逐字节解析 SSE 事件流，保留空行事件边界与多行 `data:` 换行符。
struct SSELineParser {
    private var lineBuffer: [UInt8] = []
    private var currentEventName: String?
    private var dataLines: [String] = []
    private let decoder: JSONDecoder

    init(decoder: JSONDecoder = APIClient.decoder) {
        self.decoder = decoder
    }

    mutating func feed(_ byte: UInt8) -> AgentStreamEvent? {
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

    mutating func finish() -> AgentStreamEvent? {
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

    private mutating func processLine(_ line: String) -> AgentStreamEvent? {
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
            dataLines.append(String(line.dropFirst(5)))
            return nil
        }
        return nil
    }

    private mutating func flushEvent() -> AgentStreamEvent? {
        guard currentEventName != nil || !dataLines.isEmpty else {
            return nil
        }
        let eventName = currentEventName ?? "message"
        let payload = dataLines.joined(separator: "\n")
        currentEventName = nil
        dataLines.removeAll(keepingCapacity: true)
        return decodeEvent(name: eventName, dataString: payload)
    }

    private func decodeEvent(name: String, dataString: String) -> AgentStreamEvent? {
        switch name {
        case "run_started":
            if let dto = decodeJSON(RunStartedDTO.self, from: dataString) {
                return .runStarted(runId: dto.runId ?? "")
            }
            return nil

        case "text", "message":
            return dataString.isEmpty ? nil : .text(dataString)

        case "thinking":
            return dataString.isEmpty ? nil : .thinking(dataString)

        case "tool_call":
            if let dto = decodeJSON(ToolCallDTO.self, from: dataString) {
                return .toolCall(
                    toolCallId: dto.toolCallId ?? "",
                    toolName: dto.toolName ?? "tool",
                    command: dto.command ?? ""
                )
            }
            return .toolCall(toolCallId: "", toolName: "tool", command: dataString)

        case "tool_result":
            if let dto = decodeJSON(ToolResultDTO.self, from: dataString) {
                return .toolResult(
                    toolCallId: dto.toolCallId ?? "",
                    toolName: dto.toolName ?? "tool",
                    result: dto.result ?? "",
                    status: dto.status ?? .completed
                )
            }
            return .toolResult(toolCallId: "", toolName: "tool", result: dataString, status: .completed)

        case "permission_required":
            if let approval = decodeJSON(AgentPendingApproval.self, from: dataString) {
                return .permissionRequired(approval)
            }
            return .error(String(localized: "agent.error.invalid_permission_event"))

        case "subagent":
            if let progress = decodeJSON(AgentSubagentProgress.self, from: dataString) {
                return .subagent(progress)
            }
            return nil

        case "routing_notice":
            if let notice = decodeJSON(AgentRoutingNotice.self, from: dataString) {
                return .routingNotice(notice)
            }
            return nil

        case "cancelled":
            let dto = decodeJSON(CancelledDTO.self, from: dataString)
            return .cancelled(runId: dto?.runId)

        case "error":
            let trimmed = dataString.trimmingCharacters(in: .whitespacesAndNewlines)
            return .error(trimmed.isEmpty ? String(localized: "agent.error.stream_failed") : trimmed)

        case "done":
            return .done

        default:
            return nil
        }
    }

    private func decodeJSON<T: Decodable>(_ type: T.Type, from raw: String) -> T? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8) else {
            return nil
        }
        return try? decoder.decode(type, from: data)
    }
}

private struct RunStartedDTO: Decodable {
    let runId: String?
}

private struct ToolCallDTO: Decodable {
    let toolCallId: String?
    let toolName: String?
    let command: String?
}

private struct ToolResultDTO: Decodable {
    let toolCallId: String?
    let toolName: String?
    let result: String?
    let status: AgentToolStatus?
}

private struct CancelledDTO: Decodable {
    let runId: String?
}
