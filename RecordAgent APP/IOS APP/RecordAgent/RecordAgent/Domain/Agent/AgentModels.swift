import Foundation

/// 通用类型安全 JSON 值，避免使用 `Any` 解析工具参数。
enum JSONValue: Codable, Equatable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case array([JSONValue])
    case object([String: JSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let boolValue = try? container.decode(Bool.self) {
            self = .bool(boolValue)
        } else if let doubleValue = try? container.decode(Double.self) {
            self = .number(doubleValue)
        } else if let stringValue = try? container.decode(String.self) {
            self = .string(stringValue)
        } else if let arrayValue = try? container.decode([JSONValue].self) {
            self = .array(arrayValue)
        } else if let objectValue = try? container.decode([String: JSONValue].self) {
            self = .object(objectValue)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "不支持的 JSON 值")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    var displayString: String {
        switch self {
        case .string(let value):
            return value
        case .number(let value):
            if value.rounded() == value, abs(value) < Double(Int64.max) {
                return String(Int64(value))
            }
            return String(value)
        case .bool(let value):
            return value ? "true" : "false"
        case .null:
            return "null"
        case .array(let items):
            return "[" + items.map(\.displayString).joined(separator: ", ") + "]"
        case .object(let dict):
            let pairs = dict.keys.sorted().compactMap { key -> String? in
                guard let val = dict[key] else { return nil }
                return "\(key): \(val.displayString)"
            }
            return "{ " + pairs.joined(separator: ", ") + " }"
        }
    }
}

enum AgentSessionKind: String, Codable, Equatable, Sendable {
    case general = "GENERAL"
    case project = "PROJECT"
}

enum AgentSessionStatus: String, Codable, Equatable, Sendable {
    case active = "ACTIVE"
    case deleting = "DELETING"
}

enum AgentSessionPermissionMode: String, Codable, CaseIterable, Equatable, Sendable {
    case ask = "ASK"
    case autoEdit = "AUTO_EDIT"
    case fullAccess = "FULL_ACCESS"
}

struct AgentSessionSummary: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let kind: AgentSessionKind
    let projectId: String?
    var title: String
    let lastMessagePreview: String?
    let createdAt: Date
    let updatedAt: Date
    let status: AgentSessionStatus
}

struct AgentTokenUsageSummary: Codable, Equatable, Sendable {
    let inputTokens: Int64
    let outputTokens: Int64
    let cachedInputTokens: Int64
    let totalTokens: Int64
    let turnCount: Int
    let trackedTurnCount: Int
    let modelCallCount: Int
    let reportedCallCount: Int
}

struct AgentTurnTokenUsage: Codable, Equatable, Sendable {
    let inputTokens: Int64
    let outputTokens: Int64
    let cachedInputTokens: Int64
    let totalTokens: Int64
    let durationMillis: Int64?
}

enum AgentMessageRole: String, Codable, Equatable, Sendable {
    case user = "USER"
    case assistant = "ASSISTANT"
}

enum AgentMessageStatus: String, Codable, Equatable, Sendable {
    case completed = "COMPLETED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"
}

enum AgentToolStatus: String, Codable, Equatable, Sendable {
    case running = "RUNNING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self).uppercased()
        self = AgentToolStatus(rawValue: raw) ?? .completed
    }
}

struct AgentToolExecution: Identifiable, Codable, Equatable, Sendable {
    var id: String { toolCallId }
    let toolCallId: String
    var toolName: String
    var command: String
    var output: String?
    var status: AgentToolStatus
}

enum AgentMessageBlockKind: String, Codable, Equatable, Sendable {
    case thinking = "THINKING"
    case text = "TEXT"
    case toolGroup = "TOOL_GROUP"
}

struct AgentMessageBlock: Identifiable, Equatable, Sendable {
    let id: String
    let kind: AgentMessageBlockKind
    var content: String
    var tools: [AgentToolExecution]
}

struct AgentTranscriptBlockDTO: Codable, Equatable, Sendable {
    let kind: AgentMessageBlockKind
    let content: String?
    let tools: [AgentToolExecution]?
}

struct AgentTranscriptMessageDTO: Codable, Equatable, Sendable {
    let id: String
    let turnId: String?
    let role: AgentMessageRole
    let content: String?
    let createdAt: Date?
    let status: AgentMessageStatus?
    let durationMillis: Int64?
    let tools: [AgentToolExecution]?
    let thinking: String?
    let usage: AgentTurnTokenUsage?
    let proactive: Bool?
    let blocks: [AgentTranscriptBlockDTO]?
}

struct AgentSessionDetailDTO: Codable, Equatable, Sendable {
    let summary: AgentSessionSummary
    let messages: [AgentTranscriptMessageDTO]
    let usageSummary: AgentTokenUsageSummary?
}

struct AgentChatMessage: Identifiable, Equatable, Sendable {
    let id: String
    let turnId: String?
    let role: AgentMessageRole
    var content: String
    var thinking: String?
    let createdAt: Date
    var status: AgentMessageStatus?
    var durationSeconds: Int?
    var tools: [AgentToolExecution]
    var blocks: [AgentMessageBlock]
    var proactive: Bool
    var usage: AgentTurnTokenUsage?
    var isStreaming: Bool
}

struct AgentPermissionTool: Identifiable, Codable, Equatable, Sendable {
    var id: String { toolCallId }
    let toolCallId: String
    let toolName: String
    let input: [String: JSONValue]?
    let riskDescription: String
    let index: Int
    let total: Int

    var orderedInputPairs: [(key: String, value: String)] {
        guard let input else { return [] }
        return input.keys.sorted().compactMap { key in
            guard let val = input[key] else { return nil }
            return (key: key, value: val.displayString)
        }
    }
}

struct AgentPendingApproval: Codable, Equatable, Sendable {
    let sessionId: String?
    let approvalId: String
    let runId: String
    let turnId: String?
    let partialContent: String?
    let startedAt: Date?
    let readyToResume: Bool?
    let tools: [AgentPermissionTool]
}

struct AgentPermissionDecisionInput: Codable, Equatable, Sendable {
    let toolCallId: String
    let approved: Bool
    let rememberForSession: Bool
}

struct AgentPermissionDecisionResponse: Codable, Equatable, Sendable {
    let readyToResume: Bool
    let nextTool: AgentPermissionTool?
}

struct AgentRunCancelResponse: Codable, Equatable, Sendable {
    let runId: String
    let accepted: Bool
    let status: String
}

struct AgentRoutingNotice: Codable, Equatable, Sendable {
    let code: String
    let message: String
    let requestId: String?
    let retryable: Bool?
    let retryAfterMillis: Int64?
}

struct AgentSubagentProgress: Codable, Equatable, Sendable {
    let source: String
    let agentId: String
    let eventType: String
    let content: String
}

enum AgentStreamEvent: Equatable, Sendable {
    case runStarted(runId: String)
    case text(String)
    case thinking(String)
    case toolCall(toolCallId: String, toolName: String, command: String)
    case toolResult(toolCallId: String, toolName: String, result: String, status: AgentToolStatus)
    case permissionRequired(AgentPendingApproval)
    case subagent(AgentSubagentProgress)
    case routingNotice(AgentRoutingNotice)
    case done
    case cancelled(runId: String?)
    case error(String)
}

struct AgentModelConfig: Codable, Equatable, Sendable {
    let vendor: String?
    let name: String?
    let apiKey: String?
    let temperature: Double?
    let stream: Bool?
    let baseUrl: String?
    let `protocol`: String?
    let supportsVision: Bool?

    var displayName: String {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "" : trimmed
    }
}

struct AgentModelItem: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let name: String?
    let modelName: String
    let providerId: String?
    let description: String?
    let supportsReasoning: Bool?
    let supportsVision: Bool?

    var displayTitle: String {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? modelName : trimmed
    }
}

struct AgentProviderConfig: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let name: String?
    let type: String
    let baseUrl: String?
    let `protocol`: String?
    let apiKey: String?
    let isEnabled: Bool?
    let models: [AgentModelItem]?

    var displayTitle: String {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? type : trimmed
    }
}

struct AgentFullModelConfig: Codable, Equatable, Sendable {
    let activeVendor: String?
    let activeModel: String?
    let vendor: String?
    let name: String?
    let baseUrl: String?
    let `protocol`: String?
    let providers: [AgentProviderConfig]?
}
