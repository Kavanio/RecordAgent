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

enum AgentUsageStatus: String, Codable, Equatable, Sendable {
    case complete = "COMPLETE"
    case partial = "PARTIAL"
    case unavailable = "UNAVAILABLE"

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self).uppercased()
        self = AgentUsageStatus(rawValue: raw) ?? .unavailable
    }
}

enum AgentUsagePurpose: String, Codable, Equatable, Sendable {
    case chat = "CHAT"
    case sessionTitle = "SESSION_TITLE"
    case modelConnectionTest = "MODEL_CONNECTION_TEST"
    case profileMaintenance = "PROFILE_MAINTENANCE"
    case contextCompaction = "CONTEXT_COMPACTION"
    case backgroundAgent = "BACKGROUND_AGENT"
    case unknown = "UNKNOWN"

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self).uppercased()
        self = AgentUsagePurpose(rawValue: raw) ?? .unknown
    }
}

struct AgentInputTokenBreakdown: Codable, Equatable, Sendable {
    let systemPromptTokens: Int64
    let historyTokens: Int64
    let currentUserTokens: Int64
    let toolSchemaTokens: Int64
    let toolResultTokens: Int64
    let profileContextTokens: Int64
    let memoryRecallTokens: Int64
    let ragContextTokens: Int64
    let otherTokens: Int64

    static let empty = AgentInputTokenBreakdown(
        systemPromptTokens: 0,
        historyTokens: 0,
        currentUserTokens: 0,
        toolSchemaTokens: 0,
        toolResultTokens: 0,
        profileContextTokens: 0,
        memoryRecallTokens: 0,
        ragContextTokens: 0,
        otherTokens: 0
    )

    init(
        systemPromptTokens: Int64 = 0,
        historyTokens: Int64 = 0,
        currentUserTokens: Int64 = 0,
        toolSchemaTokens: Int64 = 0,
        toolResultTokens: Int64 = 0,
        profileContextTokens: Int64 = 0,
        memoryRecallTokens: Int64 = 0,
        ragContextTokens: Int64 = 0,
        otherTokens: Int64 = 0
    ) {
        self.systemPromptTokens = max(0, systemPromptTokens)
        self.historyTokens = max(0, historyTokens)
        self.currentUserTokens = max(0, currentUserTokens)
        self.toolSchemaTokens = max(0, toolSchemaTokens)
        self.toolResultTokens = max(0, toolResultTokens)
        self.profileContextTokens = max(0, profileContextTokens)
        self.memoryRecallTokens = max(0, memoryRecallTokens)
        self.ragContextTokens = max(0, ragContextTokens)
        self.otherTokens = max(0, otherTokens)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            systemPromptTokens: (try? c.decode(Int64.self, forKey: .systemPromptTokens)) ?? 0,
            historyTokens: (try? c.decode(Int64.self, forKey: .historyTokens)) ?? 0,
            currentUserTokens: (try? c.decode(Int64.self, forKey: .currentUserTokens)) ?? 0,
            toolSchemaTokens: (try? c.decode(Int64.self, forKey: .toolSchemaTokens)) ?? 0,
            toolResultTokens: (try? c.decode(Int64.self, forKey: .toolResultTokens)) ?? 0,
            profileContextTokens: (try? c.decode(Int64.self, forKey: .profileContextTokens)) ?? 0,
            memoryRecallTokens: (try? c.decode(Int64.self, forKey: .memoryRecallTokens)) ?? 0,
            ragContextTokens: (try? c.decode(Int64.self, forKey: .ragContextTokens)) ?? 0,
            otherTokens: (try? c.decode(Int64.self, forKey: .otherTokens)) ?? 0
        )
    }
}

struct AgentToolTokenUsage: Codable, Equatable, Sendable, Identifiable {
    var id: String { toolName }
    let toolName: String
    let schemaTokens: Int64
    let resultTokens: Int64
}

struct AgentModelInvocationUsage: Codable, Equatable, Sendable, Identifiable {
    var id: String { "\(source):\(invocationId):\(modelCallIndex)" }
    let invocationId: String
    let modelCallIndex: Int
    let source: String
    let purpose: AgentUsagePurpose?
    let vendor: String?
    let model: String?
    let inputTokens: Int64?
    let outputTokens: Int64?
    let cachedInputTokens: Int64?
    let totalTokens: Int64?
    let durationMillis: Int64?
    let status: AgentUsageStatus?
    let tokenCounterId: String?
    let estimatedInputTokens: Int64?
    let estimationDeltaTokens: Int64?
    let breakdown: AgentInputTokenBreakdown?
    let toolUsages: [AgentToolTokenUsage]?
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
    let status: AgentUsageStatus?

    init(
        inputTokens: Int64,
        outputTokens: Int64,
        cachedInputTokens: Int64,
        totalTokens: Int64,
        turnCount: Int,
        trackedTurnCount: Int,
        modelCallCount: Int,
        reportedCallCount: Int,
        status: AgentUsageStatus? = nil
    ) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.cachedInputTokens = cachedInputTokens
        self.totalTokens = totalTokens
        self.turnCount = turnCount
        self.trackedTurnCount = trackedTurnCount
        self.modelCallCount = modelCallCount
        self.reportedCallCount = reportedCallCount
        self.status = status
    }
}

struct AgentTurnTokenUsage: Codable, Equatable, Sendable {
    let inputTokens: Int64
    let outputTokens: Int64
    let cachedInputTokens: Int64
    let totalTokens: Int64
    let modelCallCount: Int?
    let reportedCallCount: Int?
    let status: AgentUsageStatus?
    let calls: [AgentModelInvocationUsage]?
    let estimatedInputTokens: Int64?
    let estimationDeltaTokens: Int64?
    let breakdown: AgentInputTokenBreakdown?
    let toolUsages: [AgentToolTokenUsage]?
    let durationMillis: Int64?

    init(
        inputTokens: Int64,
        outputTokens: Int64,
        cachedInputTokens: Int64,
        totalTokens: Int64,
        modelCallCount: Int? = nil,
        reportedCallCount: Int? = nil,
        status: AgentUsageStatus? = nil,
        calls: [AgentModelInvocationUsage]? = nil,
        estimatedInputTokens: Int64? = nil,
        estimationDeltaTokens: Int64? = nil,
        breakdown: AgentInputTokenBreakdown? = nil,
        toolUsages: [AgentToolTokenUsage]? = nil,
        durationMillis: Int64? = nil
    ) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
        self.cachedInputTokens = cachedInputTokens
        self.totalTokens = totalTokens
        self.modelCallCount = modelCallCount
        self.reportedCallCount = reportedCallCount
        self.status = status
        self.calls = calls
        self.estimatedInputTokens = estimatedInputTokens
        self.estimationDeltaTokens = estimationDeltaTokens
        self.breakdown = breakdown
        self.toolUsages = toolUsages
        self.durationMillis = durationMillis
    }
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
    var images: [AgentChatImageAttachment]

    init(
        id: String,
        turnId: String?,
        role: AgentMessageRole,
        content: String,
        thinking: String?,
        createdAt: Date,
        status: AgentMessageStatus?,
        durationSeconds: Int?,
        tools: [AgentToolExecution],
        blocks: [AgentMessageBlock],
        proactive: Bool,
        usage: AgentTurnTokenUsage?,
        isStreaming: Bool,
        images: [AgentChatImageAttachment] = []
    ) {
        self.id = id
        self.turnId = turnId
        self.role = role
        self.content = content
        self.thinking = thinking
        self.createdAt = createdAt
        self.status = status
        self.durationSeconds = durationSeconds
        self.tools = tools
        self.blocks = blocks
        self.proactive = proactive
        self.usage = usage
        self.isStreaming = isStreaming
        self.images = images
    }
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
    let contextWindow: Int64?

    init(
        vendor: String?,
        name: String?,
        apiKey: String?,
        temperature: Double?,
        stream: Bool?,
        baseUrl: String?,
        protocol protocolName: String?,
        supportsVision: Bool?,
        contextWindow: Int64? = nil
    ) {
        self.vendor = vendor
        self.name = name
        self.apiKey = apiKey
        self.temperature = temperature
        self.stream = stream
        self.baseUrl = baseUrl
        self.`protocol` = protocolName
        self.supportsVision = supportsVision
        self.contextWindow = contextWindow
    }

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
    let contextWindow: Int64?

    init(
        id: String,
        name: String?,
        modelName: String,
        providerId: String?,
        description: String?,
        supportsReasoning: Bool?,
        supportsVision: Bool?,
        contextWindow: Int64? = nil
    ) {
        self.id = id
        self.name = name
        self.modelName = modelName
        self.providerId = providerId
        self.description = description
        self.supportsReasoning = supportsReasoning
        self.supportsVision = supportsVision
        self.contextWindow = contextWindow
    }

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

enum AgentRecordType: String, Codable, Equatable, Sendable {
    case quick
    case learning
    case weeklyReview = "weekly_review"
    case reading
    case journal
    case unknown

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = AgentRecordType(rawValue: raw) ?? .unknown
    }
}

struct AgentRecordReferenceOption: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let recordDate: String
    let type: AgentRecordType
    let title: String?
    let summary: String
    let tags: [String]
    let updatedAt: Date?

    var displayTitle: String {
        let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? String(localized: "agent.reference.untitled") : trimmed
    }
}

struct AgentRecordReferencePage: Codable, Equatable, Sendable {
    let items: [AgentRecordReferenceOption]
    let hasMore: Bool
    let nextOffset: Int
}

struct AgentRecordDetail: Identifiable, Codable, Equatable, Sendable {
    let id: String
    let recordDate: String
    let type: AgentRecordType
    let title: String?
    let contentText: String?
    let tags: [String]?
    let updatedAt: Date?

    var displayTitle: String {
        let trimmed = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? String(localized: "agent.reference.untitled") : trimmed
    }
}

struct AgentChatImagePayload: Codable, Equatable, Sendable {
    let mediaType: String
    let dataBase64: String
}

struct AgentChatImageAttachment: Identifiable, Equatable, Sendable {
    let id: UUID
    let mediaType: String
    let data: Data
    let dataBase64: String

    var byteCount: Int { data.count }

    var payload: AgentChatImagePayload {
        AgentChatImagePayload(mediaType: mediaType, dataBase64: dataBase64)
    }
}
