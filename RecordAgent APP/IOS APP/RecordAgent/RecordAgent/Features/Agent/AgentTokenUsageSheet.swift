import Charts
import SwiftUI

/// 会话与单轮次上下文及 Token 用量详情面板（对齐桌面 `TokenUsagePanel`）。
struct AgentTokenUsageSheet: View {
    @ObservedObject var viewModel: AgentChatViewModel
    let initialSelectedMessageId: String?
    let onClose: () -> Void

    @State private var selectedMessageId: String?
    @State private var selectedBreakdownKey: AgentTokenBreakdownKey?
    @State private var showTools = false
    @State private var showCalls = false

    init(
        viewModel: AgentChatViewModel,
        initialSelectedMessageId: String? = nil,
        onClose: @escaping () -> Void
    ) {
        self.viewModel = viewModel
        self.initialSelectedMessageId = initialSelectedMessageId
        self.onClose = onClose
        _selectedMessageId = State(initialValue: initialSelectedMessageId)
    }

    private var turnOptions: [AgentTokenUsageTurnOption] {
        viewModel.turnUsageOptions
    }

    private var activeSnapshot: AgentTokenUsageSnapshot? {
        viewModel.tokenSnapshot(for: selectedMessageId)
    }

    var body: some View {
        VStack(spacing: 0) {
            WorkspaceSheetHeader(
                title: "agent.usage.sheet_title",
                subtitle: "agent.usage.sheet_subtitle",
                onClose: onClose
            )
            .padding(.horizontal, 24)
            .padding(.top, 26)
            .padding(.bottom, 16)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    contextOverviewCard

                    if !turnOptions.isEmpty {
                        scopePicker
                    }

                    if let snapshot = activeSnapshot {
                        usageOverviewCard(snapshot)

                        if snapshot.hasBreakdown {
                            breakdownCard(snapshot)
                        }

                        if !snapshot.toolUsages.isEmpty || !snapshot.calls.isEmpty {
                            detailsDisclosureCard(snapshot)
                        }
                    } else {
                        emptyUsageCard
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .tint(AppColors.primaryBlue)
        .workspaceSheet(compact: false)
        .onChange(of: turnOptions.map(\.messageId)) { _, ids in
            if let selectedMessageId, !ids.contains(selectedMessageId) {
                self.selectedMessageId = nil
            }
        }
    }

    // MARK: - 当前会话上下文概览

    private var contextOverviewCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "cpu")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.primaryBlue)
                Text("agent.usage.context_section")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Spacer(minLength: 0)
                if viewModel.activeSessionIsReadOnly {
                    Text("agent.feishu.readonly_badge")
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(uiColor: .tertiarySystemFill), in: Capsule())
                }
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                contextInfoCell(
                    title: "agent.usage.context.model",
                    value: viewModel.currentModelDisplayName
                )
                contextInfoCell(
                    title: "agent.usage.context.vision",
                    value: String(localized: viewModel.currentModelSupportsVision
                        ? "agent.usage.context.vision_on"
                        : "agent.usage.context.vision_off")
                )
                contextInfoCell(
                    title: "agent.usage.context.permission",
                    value: permissionModeDisplayText
                )
                contextInfoCell(
                    title: "agent.usage.context.turns",
                    value: "\(turnOptions.count)"
                )
            }

            contextWindowProgressCard
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 20, style: .continuous)
        )
    }

    private var contextWindowProgressCard: some View {
        let metrics = viewModel.contextWindowMetrics
        let tintColor: Color = {
            guard let ratio = metrics.occupancyRatio else { return AppColors.primaryBlue }
            if ratio >= 0.90 { return AppColors.overviewExpense }
            if ratio >= 0.70 { return Color.orange }
            return AppColors.primaryBlue
        }()

        return VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("agent.usage.context.window")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)
                if metrics.isLimitEstimated, metrics.contextWindowLimit != nil {
                    Text("agent.usage.context.window_estimated")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                Spacer(minLength: 8)
                if let current = metrics.currentContextTokens {
                    if let limit = metrics.contextWindowLimit, let pct = metrics.occupancyPercentText {
                        Text(verbatim: "\(AgentTokenUsageAnalyzer.formatTokenCount(current)) / \(AgentTokenUsageAnalyzer.formatTokenCount(limit)) (\(pct))")
                            .font(.caption.weight(.semibold).monospacedDigit())
                            .foregroundStyle(tintColor)
                    } else {
                        Text(verbatim: "\(AgentTokenUsageAnalyzer.formatTokenCount(current)) / —")
                            .font(.caption.weight(.semibold).monospacedDigit())
                            .foregroundStyle(.primary)
                    }
                } else if let limit = metrics.contextWindowLimit {
                    Text(verbatim: "0 / \(AgentTokenUsageAnalyzer.formatTokenCount(limit))")
                        .font(.caption.weight(.medium).monospacedDigit())
                        .foregroundStyle(.secondary)
                } else {
                    Text("agent.usage.context.window_unavailable")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if let ratio = metrics.occupancyRatio {
                ProgressView(value: ratio)
                    .tint(tintColor)
            }
        }
        .padding(12)
        .background(
            Color(uiColor: .tertiarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
    }

    private func contextInfoCell(title: LocalizedStringKey, value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(verbatim: value)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(
            Color(uiColor: .tertiarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
    }

    private var permissionModeDisplayText: String {
        switch viewModel.permissionMode {
        case .ask: return String(localized: "agent.permission_mode.short.ask")
        case .autoEdit: return String(localized: "agent.permission_mode.short.auto_edit")
        case .fullAccess: return String(localized: "agent.permission_mode.short.full_access")
        }
    }

    // MARK: - 统计范围选择（整个会话 / 各轮次）

    private var scopePicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                scopeChip(
                    title: String(localized: "agent.usage.scope.session"),
                    isSelected: selectedMessageId == nil
                ) {
                    selectedMessageId = nil
                    selectedBreakdownKey = nil
                }

                ForEach(turnOptions.reversed()) { option in
                    let turnLabel = String(localized: "agent.usage.scope.turn \(option.turnNumber)")
                    scopeChip(
                        title: turnLabel,
                        isSelected: selectedMessageId == option.messageId
                    ) {
                        selectedMessageId = option.messageId
                        selectedBreakdownKey = nil
                    }
                }
            }
            .padding(.horizontal, 2)
        }
    }

    private func scopeChip(title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: title)
                .font(.caption.weight(isSelected ? .semibold : .medium))
                .foregroundStyle(isSelected ? .white : .primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(
                    isSelected ? AppColors.primaryBlue : Color(uiColor: .secondarySystemGroupedBackground),
                    in: Capsule()
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Token 总览指标

    private func usageOverviewCard(_ snapshot: AgentTokenUsageSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text(selectedMessageId == nil ? "agent.usage.scope.session" : "agent.usage.scope.turn_detail")
                    .font(.subheadline.weight(.semibold))
                Spacer(minLength: 8)
                if snapshot.modelCallCount > 0 {
                    Text(verbatim: "\(snapshot.modelCallCount) · \(AgentTokenUsageAnalyzer.formatDuration(snapshot.durationMillis))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            if snapshot.hasReportedUsage {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    metricCell(
                        label: "agent.usage.metric.total",
                        value: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.totalTokens),
                        emphasized: true
                    )
                    metricCell(
                        label: "agent.usage.metric.input",
                        value: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.inputTokens),
                        emphasized: false
                    )
                    metricCell(
                        label: "agent.usage.metric.output",
                        value: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.outputTokens),
                        emphasized: false
                    )
                    metricCell(
                        label: "agent.usage.metric.cached",
                        value: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.cachedInputTokens),
                        emphasized: false
                    )
                    metricCell(
                        label: "agent.usage.metric.reported_calls",
                        value: "\(snapshot.reportedCallCount) / \(snapshot.modelCallCount)",
                        emphasized: false
                    )
                    metricCell(
                        label: "agent.usage.metric.duration",
                        value: AgentTokenUsageAnalyzer.formatDuration(snapshot.durationMillis),
                        emphasized: false
                    )
                }
            } else {
                Text("agent.usage.provider_unavailable")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if snapshot.status == .partial {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "info.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 1)
                    Text("agent.usage.partial_notice")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    Color(uiColor: .tertiarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
            }
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 20, style: .continuous)
        )
    }

    private func metricCell(label: LocalizedStringKey, value: String, emphasized: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(verbatim: value)
                .font((emphasized ? Font.title3 : Font.headline).weight(.semibold).monospacedDigit())
                .foregroundStyle(emphasized ? AppColors.primaryBlue : .primary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(
            Color(uiColor: .tertiarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
    }

    // MARK: - 输入 Token 构成占比饼图与明细

    private func breakdownCard(_ snapshot: AgentTokenUsageSnapshot) -> some View {
        let segments = AgentTokenUsageAnalyzer.breakdownSegments(from: snapshot.breakdown)
        let total = segments.reduce(Int64(0)) { $0 + $1.tokens }
        let activeSegment = segments.first { $0.key == selectedBreakdownKey }

        return VStack(alignment: .leading, spacing: 14) {
            Text("agent.usage.breakdown.title")
                .font(.subheadline.weight(.semibold))

            if !segments.isEmpty {
                ZStack {
                    Chart(segments) { segment in
                        SectorMark(
                            angle: .value("Tokens", segment.tokens),
                            innerRadius: .ratio(0.62),
                            angularInset: 1.5
                        )
                        .cornerRadius(4)
                        .foregroundStyle(Self.color(for: segment.key))
                        .opacity(selectedBreakdownKey == nil || selectedBreakdownKey == segment.key ? 1.0 : 0.35)
                    }
                    .frame(height: 184)

                    VStack(spacing: 2) {
                        Text(activeSegment.map { LocalizedStringKey($0.key.localizedTitleKey) }
                            ?? LocalizedStringKey("agent.usage.breakdown.input_total"))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(activeSegment?.tokens ?? total))
                            .font(.headline.weight(.bold).monospacedDigit())
                            .foregroundStyle(.primary)
                        if let activeSegment {
                            Text(verbatim: AgentTokenUsageAnalyzer.formatPercentage(activeSegment.share))
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: 96)
                    .multilineTextAlignment(.center)
                }

                VStack(spacing: 6) {
                    ForEach(segments) { segment in
                        let isSelected = selectedBreakdownKey == segment.key
                        Button {
                            selectedBreakdownKey = isSelected ? nil : segment.key
                        } label: {
                            HStack(spacing: 10) {
                                Circle()
                                    .fill(Self.color(for: segment.key))
                                    .frame(width: 9, height: 9)
                                Text(LocalizedStringKey(segment.key.localizedTitleKey))
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                Spacer(minLength: 8)
                                Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(segment.tokens))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                                Text(verbatim: AgentTokenUsageAnalyzer.formatPercentage(segment.share))
                                    .font(.caption.weight(.semibold).monospacedDigit())
                                    .foregroundStyle(isSelected ? AppColors.primaryBlue : .primary)
                                    .frame(minWidth: 42, alignment: .trailing)
                            }
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(
                                isSelected ? AppColors.primaryBlue.opacity(0.09) : Color.clear,
                                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                            )
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Text("agent.usage.breakdown.note")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 20, style: .continuous)
        )
    }

    // MARK: - 工具与模型调用明细

    private func detailsDisclosureCard(_ snapshot: AgentTokenUsageSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if !snapshot.toolUsages.isEmpty {
                DisclosureGroup(isExpanded: $showTools) {
                    VStack(spacing: 8) {
                        ForEach(snapshot.toolUsages) { tool in
                            HStack {
                                Text(verbatim: tool.toolName)
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                Spacer(minLength: 8)
                                Text(verbatim: "Schema \(AgentTokenUsageAnalyzer.formatTokenCount(tool.schemaTokens)) · Result \(AgentTokenUsageAnalyzer.formatTokenCount(tool.resultTokens))")
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 2)
                        }
                    }
                    .padding(.top, 8)
                } label: {
                    HStack {
                        Text("agent.usage.tools_title")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                        Spacer()
                        Text(verbatim: "\(snapshot.toolUsages.count)")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if !snapshot.toolUsages.isEmpty && !snapshot.calls.isEmpty {
                Divider()
            }

            if !snapshot.calls.isEmpty {
                DisclosureGroup(isExpanded: $showCalls) {
                    VStack(spacing: 8) {
                        ForEach(Array(snapshot.calls.enumerated()), id: \.offset) { idx, call in
                            VStack(alignment: .leading, spacing: 3) {
                                HStack {
                                    Text(verbatim: "#\(idx + 1) \(call.model ?? "-")")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(.primary)
                                        .lineLimit(1)
                                    Spacer(minLength: 8)
                                    Text(verbatim: AgentTokenUsageAnalyzer.formatDuration(call.durationMillis ?? 0))
                                        .font(.caption2.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                }
                                let inStr = call.inputTokens.map { AgentTokenUsageAnalyzer.formatTokenCount($0) } ?? "—"
                                let outStr = call.outputTokens.map { AgentTokenUsageAnalyzer.formatTokenCount($0) } ?? "—"
                                let cachedStr = call.cachedInputTokens.map { AgentTokenUsageAnalyzer.formatTokenCount($0) } ?? "—"
                                Text(verbatim: "In \(inStr) · Out \(outStr) · Cached \(cachedStr)")
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 2)
                        }
                    }
                    .padding(.top, 8)
                } label: {
                    HStack {
                        Text("agent.usage.calls_title")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                        Spacer()
                        Text(verbatim: "\(snapshot.calls.count)")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 20, style: .continuous)
        )
    }

    private var emptyUsageCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("agent.usage.empty_title")
                .font(.subheadline.weight(.semibold))
            Text("agent.usage.empty_desc")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 20, style: .continuous)
        )
    }

    private static func color(for key: AgentTokenBreakdownKey) -> Color {
        switch key {
        case .systemPrompt: return Color(red: 0.31, green: 0.55, blue: 0.96)
        case .history: return Color(red: 0.48, green: 0.42, blue: 0.90)
        case .currentUser: return Color(red: 0.22, green: 0.69, blue: 0.61)
        case .toolSchema: return Color(red: 0.95, green: 0.62, blue: 0.22)
        case .toolResult: return Color(red: 0.92, green: 0.42, blue: 0.36)
        case .profileContext: return Color(red: 0.72, green: 0.44, blue: 0.85)
        case .memoryRecall: return Color(red: 0.27, green: 0.72, blue: 0.84)
        case .ragContext: return Color(red: 0.36, green: 0.74, blue: 0.44)
        case .other: return Color(red: 0.60, green: 0.64, blue: 0.70)
        }
    }
}
