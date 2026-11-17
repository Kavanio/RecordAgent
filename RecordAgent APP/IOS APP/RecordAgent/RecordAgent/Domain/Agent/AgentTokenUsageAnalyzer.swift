import Foundation

/// 输入 Token 构成估算的九个固定维度，与后端 `InputTokenBreakdown` 一一对应。
enum AgentTokenBreakdownKey: String, CaseIterable, Identifiable, Sendable {
    case systemPrompt = "systemPromptTokens"
    case history = "historyTokens"
    case currentUser = "currentUserTokens"
    case toolSchema = "toolSchemaTokens"
    case toolResult = "toolResultTokens"
    case profileContext = "profileContextTokens"
    case memoryRecall = "memoryRecallTokens"
    case ragContext = "ragContextTokens"
    case other = "otherTokens"

    var id: String { rawValue }

    var localizedTitleKey: String {
        switch self {
        case .systemPrompt: return "agent.usage.breakdown.system_prompt"
        case .history: return "agent.usage.breakdown.history"
        case .currentUser: return "agent.usage.breakdown.current_user"
        case .toolSchema: return "agent.usage.breakdown.tool_schema"
        case .toolResult: return "agent.usage.breakdown.tool_result"
        case .profileContext: return "agent.usage.breakdown.profile_context"
        case .memoryRecall: return "agent.usage.breakdown.memory_recall"
        case .ragContext: return "agent.usage.breakdown.rag_context"
        case .other: return "agent.usage.breakdown.other"
        }
    }

    func value(in breakdown: AgentInputTokenBreakdown) -> Int64 {
        switch self {
        case .systemPrompt: return breakdown.systemPromptTokens
        case .history: return breakdown.historyTokens
        case .currentUser: return breakdown.currentUserTokens
        case .toolSchema: return breakdown.toolSchemaTokens
        case .toolResult: return breakdown.toolResultTokens
        case .profileContext: return breakdown.profileContextTokens
        case .memoryRecall: return breakdown.memoryRecallTokens
        case .ragContext: return breakdown.ragContextTokens
        case .other: return breakdown.otherTokens
        }
    }
}

struct AgentTokenBreakdownSegment: Identifiable, Equatable, Sendable {
    var id: String { key.id }
    let key: AgentTokenBreakdownKey
    let tokens: Int64
    let share: Double
}

struct AgentTokenUsageTurnOption: Identifiable, Equatable, Sendable {
    var id: String { messageId }
    let messageId: String
    let turnNumber: Int
    let promptPreview: String?
    let usage: AgentTurnTokenUsage
}

struct AgentTokenUsageSnapshot: Equatable, Sendable {
    let inputTokens: Int64
    let outputTokens: Int64
    let cachedInputTokens: Int64
    let totalTokens: Int64
    let modelCallCount: Int
    let reportedCallCount: Int
    let status: AgentUsageStatus
    let calls: [AgentModelInvocationUsage]
    let estimatedInputTokens: Int64
    let estimationDeltaTokens: Int64?
    let breakdown: AgentInputTokenBreakdown
    let toolUsages: [AgentToolTokenUsage]
    let durationMillis: Int64

    var hasReportedUsage: Bool {
        reportedCallCount > 0 || totalTokens > 0 || inputTokens > 0 || outputTokens > 0
    }

    var hasBreakdown: Bool {
        estimatedInputTokens > 0 || !AgentTokenUsageAnalyzer.breakdownSegments(from: breakdown).isEmpty
    }
}

/// 负责聚合并格式化会话级与单轮次的 Token 用量及输入构成分布。
enum AgentTokenUsageAnalyzer {
    static func turnOptions(from messages: [AgentChatMessage]) -> [AgentTokenUsageTurnOption] {
        var options: [AgentTokenUsageTurnOption] = []
        var lastUserPreview: String?
        var turnNumber = 0

        for message in messages {
            if message.role == .user {
                let trimmed = message.content.trimmingCharacters(in: .whitespacesAndNewlines)
                lastUserPreview = trimmed.isEmpty ? nil : trimmed
            } else if message.role == .assistant, let usage = message.usage {
                turnNumber += 1
                options.append(AgentTokenUsageTurnOption(
                    messageId: message.id,
                    turnNumber: turnNumber,
                    promptPreview: lastUserPreview,
                    usage: usage
                ))
            }
        }
        return options
    }

    static func snapshot(for turn: AgentTurnTokenUsage) -> AgentTokenUsageSnapshot {
        let fallbackCalls = turn.calls ?? []
        let inferredModelCount = turn.modelCallCount ?? (!fallbackCalls.isEmpty ? fallbackCalls.count : 1)
        let inferredReportedCount = turn.reportedCallCount ?? (turn.totalTokens > 0 || turn.inputTokens > 0 ? inferredModelCount : 0)
        let resolvedStatus = turn.status ?? (inferredReportedCount > 0 ? .complete : .unavailable)
        let resolvedBreakdown = turn.breakdown ?? .empty
        let resolvedEstimated = turn.estimatedInputTokens ?? sumEstimated(resolvedBreakdown)

        return AgentTokenUsageSnapshot(
            inputTokens: turn.inputTokens,
            outputTokens: turn.outputTokens,
            cachedInputTokens: turn.cachedInputTokens,
            totalTokens: turn.totalTokens,
            modelCallCount: inferredModelCount,
            reportedCallCount: inferredReportedCount,
            status: resolvedStatus,
            calls: fallbackCalls,
            estimatedInputTokens: resolvedEstimated,
            estimationDeltaTokens: turn.estimationDeltaTokens,
            breakdown: resolvedBreakdown,
            toolUsages: turn.toolUsages ?? [],
            durationMillis: turn.durationMillis ?? 0
        )
    }

    static func aggregateSessionUsage(
        turns: [AgentTokenUsageTurnOption],
        summary: AgentTokenUsageSummary?
    ) -> AgentTokenUsageSnapshot? {
        if turns.isEmpty && summary == nil {
            return nil
        }

        let snapshots = turns.map { snapshot(for: $0.usage) }
        var systemPrompt: Int64 = 0
        var history: Int64 = 0
        var currentUser: Int64 = 0
        var toolSchema: Int64 = 0
        var toolResult: Int64 = 0
        var profileContext: Int64 = 0
        var memoryRecall: Int64 = 0
        var ragContext: Int64 = 0
        var other: Int64 = 0

        var toolOrder: [String] = []
        var toolTotals: [String: (schema: Int64, result: Int64)] = [:]

        for item in snapshots {
            let b = item.breakdown
            systemPrompt += b.systemPromptTokens
            history += b.historyTokens
            currentUser += b.currentUserTokens
            toolSchema += b.toolSchemaTokens
            toolResult += b.toolResultTokens
            profileContext += b.profileContextTokens
            memoryRecall += b.memoryRecallTokens
            ragContext += b.ragContextTokens
            other += b.otherTokens

            for tool in item.toolUsages {
                if toolTotals[tool.toolName] == nil {
                    toolOrder.append(tool.toolName)
                }
                let current = toolTotals[tool.toolName] ?? (0, 0)
                toolTotals[tool.toolName] = (
                    current.schema + tool.schemaTokens,
                    current.result + tool.resultTokens
                )
            }
        }

        let aggregatedBreakdown = AgentInputTokenBreakdown(
            systemPromptTokens: systemPrompt,
            historyTokens: history,
            currentUserTokens: currentUser,
            toolSchemaTokens: toolSchema,
            toolResultTokens: toolResult,
            profileContextTokens: profileContext,
            memoryRecallTokens: memoryRecall,
            ragContextTokens: ragContext,
            otherTokens: other
        )

        let aggregatedTools = toolOrder.compactMap { name -> AgentToolTokenUsage? in
            guard let counts = toolTotals[name] else { return nil }
            return AgentToolTokenUsage(
                toolName: name,
                schemaTokens: counts.schema,
                resultTokens: counts.result
            )
        }

        let fallbackStatus: AgentUsageStatus
        if snapshots.isEmpty {
            fallbackStatus = summary?.status ?? ((summary?.reportedCallCount ?? 0) > 0 ? .complete : .unavailable)
        } else if snapshots.allSatisfy({ $0.status == .unavailable }) {
            fallbackStatus = .unavailable
        } else if snapshots.contains(where: { $0.status != .complete }) {
            fallbackStatus = .partial
        } else {
            fallbackStatus = .complete
        }

        let deltas = snapshots.compactMap(\.estimationDeltaTokens)
        let aggregatedDelta: Int64? = deltas.isEmpty ? nil : deltas.reduce(0, +)

        return AgentTokenUsageSnapshot(
            inputTokens: summary?.inputTokens ?? snapshots.reduce(0) { $0 + $1.inputTokens },
            outputTokens: summary?.outputTokens ?? snapshots.reduce(0) { $0 + $1.outputTokens },
            cachedInputTokens: summary?.cachedInputTokens ?? snapshots.reduce(0) { $0 + $1.cachedInputTokens },
            totalTokens: summary?.totalTokens ?? snapshots.reduce(0) { $0 + $1.totalTokens },
            modelCallCount: summary?.modelCallCount ?? snapshots.reduce(0) { $0 + $1.modelCallCount },
            reportedCallCount: summary?.reportedCallCount ?? snapshots.reduce(0) { $0 + $1.reportedCallCount },
            status: summary?.status ?? fallbackStatus,
            calls: snapshots.flatMap(\.calls),
            estimatedInputTokens: snapshots.reduce(0) { $0 + $1.estimatedInputTokens },
            estimationDeltaTokens: aggregatedDelta,
            breakdown: aggregatedBreakdown,
            toolUsages: aggregatedTools,
            durationMillis: snapshots.reduce(0) { $0 + $1.durationMillis }
        )
    }

    static func breakdownSegments(from breakdown: AgentInputTokenBreakdown) -> [AgentTokenBreakdownSegment] {
        let nonZero = AgentTokenBreakdownKey.allCases.compactMap { key -> (AgentTokenBreakdownKey, Int64)? in
            let value = key.value(in: breakdown)
            return value > 0 ? (key, value) : nil
        }
        let total = nonZero.reduce(Int64(0)) { $0 + $1.1 }
        guard total > 0 else { return [] }
        return nonZero.map { key, value in
            AgentTokenBreakdownSegment(
                key: key,
                tokens: value,
                share: Double(value) / Double(total)
            )
        }
    }

    static func formatTokenCount(_ value: Int64) -> String {
        let safe = max(0, value)
        return tokenFormatter.string(from: NSNumber(value: safe)) ?? "\(safe)"
    }

    static func formatCompactTokenCount(_ value: Int64) -> String {
        let safe = max(0, value)
        if safe >= 1_000_000 {
            let m = Double(safe) / 1_000_000.0
            return m >= 10 ? String(format: "%.0fM", m) : String(format: "%.1fM", m)
        }
        if safe >= 1_000 {
            let k = Double(safe) / 1_000.0
            return k >= 10 ? String(format: "%.0fk", k) : String(format: "%.1fk", k)
        }
        return "\(safe)"
    }

    static func formatPercentage(_ share: Double) -> String {
        let pct = max(0, share * 100.0)
        if pct < 0.1 { return "<0.1%" }
        if pct < 1.0 { return String(format: "%.1f%%", pct) }
        return "\(Int(pct.rounded()))%"
    }

    static func formatDuration(_ durationMillis: Int64) -> String {
        guard durationMillis > 0 else { return "—" }
        if durationMillis < 1000 { return "\(durationMillis) ms" }
        return String(format: "%.1f s", Double(durationMillis) / 1000.0)
    }

    static func formatTimestampSeconds(_ date: Date) -> String {
        timestampSecondsFormatter.string(from: date)
    }

    static func latestContextMetrics(
        from messages: [AgentChatMessage],
        modelName: String?,
        vendor: String?,
        configuredLimit: Int64?
    ) -> AgentContextWindowMetrics {
        let latestUsage = messages.reversed().first(where: { $0.role == .assistant && $0.usage != nil })?.usage
        let latestCall = latestUsage?.calls?.last
        let currentContextTokens: Int64?
        if let callInput = latestCall?.inputTokens {
            currentContextTokens = max(0, callInput)
        } else if let callEstimate = latestCall?.estimatedInputTokens, callEstimate > 0 {
            currentContextTokens = callEstimate
        } else if let usage = latestUsage {
            if usage.inputTokens > 0 {
                currentContextTokens = usage.inputTokens
            } else if let est = usage.estimatedInputTokens, est > 0 {
                currentContextTokens = est
            } else {
                currentContextTokens = nil
            }
        } else {
            currentContextTokens = nil
        }

        let resolvedLimit = AgentModelContextCatalog.contextWindow(
            modelName: modelName,
            vendor: vendor,
            configuredLimit: configuredLimit
        )
        let ratio: Double?
        let percentText: String?
        if let currentContextTokens, let limit = resolvedLimit?.limit, limit > 0 {
            let raw = Double(currentContextTokens) / Double(limit)
            ratio = min(1.0, max(0.0, raw))
            percentText = formatPercentage(ratio ?? 0)
        } else {
            ratio = nil
            percentText = nil
        }

        return AgentContextWindowMetrics(
            currentContextTokens: currentContextTokens,
            contextWindowLimit: resolvedLimit?.limit,
            isLimitEstimated: resolvedLimit?.isEstimated ?? false,
            occupancyRatio: ratio,
            occupancyPercentText: percentText
        )
    }

    private static func sumEstimated(_ breakdown: AgentInputTokenBreakdown) -> Int64 {
        breakdown.systemPromptTokens
            + breakdown.historyTokens
            + breakdown.currentUserTokens
            + breakdown.toolSchemaTokens
            + breakdown.toolResultTokens
            + breakdown.profileContextTokens
            + breakdown.memoryRecallTokens
            + breakdown.ragContextTokens
    }

    private static let tokenFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ","
        return formatter
    }()

    private static let timestampSecondsFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter
    }()
}

struct AgentContextWindowMetrics: Equatable, Sendable {
    let currentContextTokens: Int64?
    let contextWindowLimit: Int64?
    let isLimitEstimated: Bool
    let occupancyRatio: Double?
    let occupancyPercentText: String?
}

/// 解析当前模型上下文窗口上限：优先使用服务端配置值，否则按模型族预设（对齐桌面 `modelPreset.ts`）。
enum AgentModelContextCatalog {
    static func contextWindow(
        modelName: String?,
        vendor: String?,
        configuredLimit: Int64?
    ) -> (limit: Int64, isEstimated: Bool)? {
        if let configuredLimit, configuredLimit > 0 {
            return (configuredLimit, false)
        }
        let normalized = (modelName ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let normalizedVendor = (vendor ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalized.isEmpty else { return nil }

        if normalized.contains("gemini-2.5-pro") || normalized.contains("gemini-1.5-pro") {
            return (2_000_000, true)
        }
        if normalized.contains("gemini") || normalizedVendor == "gemini" {
            return (1_000_000, true)
        }
        if normalized.contains("gpt-4.1") {
            return (1_000_000, true)
        }
        if normalized.contains("claude") || normalizedVendor == "anthropic" {
            return (200_000, true)
        }
        if normalized.hasPrefix("o1") || normalized.hasPrefix("o3") || normalized.hasPrefix("o4") {
            return (200_000, true)
        }
        if normalized.contains("gpt-4o") || normalized.contains("gpt-4-turbo") {
            return (128_000, true)
        }
        if normalized.contains("deepseek-v4") || normalized.contains("deepseek-v3.2") {
            return (128_000, true)
        }
        if normalized.contains("deepseek") || normalizedVendor == "deepseek" {
            return (64_000, true)
        }
        if normalized.contains("qwen") || normalized.contains("glm-4")
            || normalized.contains("kimi") || normalized.contains("moonshot") {
            return (128_000, true)
        }
        return nil
    }
}
