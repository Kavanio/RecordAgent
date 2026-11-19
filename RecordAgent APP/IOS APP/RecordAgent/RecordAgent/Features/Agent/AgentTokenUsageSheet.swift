import Charts
import SwiftUI

/// 会话与单轮次上下文及 Token 用量遥测面板（对齐桌面 `TokenUsagePanel` 并针对移动端重塑视觉层次）。
struct AgentTokenUsageSheet: View {
    @ObservedObject var viewModel: AgentChatViewModel
    let initialSelectedMessageId: String?
    let onClose: () -> Void

    @State private var selectedMessageId: String?
    @State private var selectedBreakdownKey: AgentTokenBreakdownKey?
    @State private var showTools = false
    @State private var showCalls = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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

    private var selectedTurnOption: AgentTokenUsageTurnOption? {
        guard let selectedMessageId else { return nil }
        return turnOptions.first { $0.messageId == selectedMessageId }
    }

    private var activeSnapshot: AgentTokenUsageSnapshot? {
        viewModel.tokenSnapshot(for: selectedMessageId)
    }

    private var activeVendor: String? {
        viewModel.modelConfig?.vendor ?? viewModel.fullModelConfig?.activeVendor
    }

    private var activeModelRawName: String? {
        viewModel.modelConfig?.name ?? viewModel.fullModelConfig?.activeModel
    }

    var body: some View {
        VStack(spacing: 0) {
            WorkspaceSheetHeader(
                title: "agent.usage.sheet_title",
                subtitle: "agent.usage.sheet_subtitle",
                onClose: onClose
            )
            .padding(.horizontal, 22)
            .padding(.top, 24)
            .padding(.bottom, 14)

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 16) {
                    contextTelemetryHeroCard

                    if !turnOptions.isEmpty {
                        scopeTimelinePicker
                    }

                    if let snapshot = activeSnapshot {
                        usageThroughputCard(snapshot)

                        if snapshot.hasBreakdown {
                            breakdownSpectrumCard(snapshot)
                        }

                        if !snapshot.toolUsages.isEmpty || !snapshot.calls.isEmpty {
                            inspectorDisclosureCard(snapshot)
                        }
                    } else {
                        emptyUsageCard
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 4)
                .padding(.bottom, 32)
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

    // MARK: - 模块一：模型身份与上下文窗口水位遥测舱

    private var contextTelemetryHeroCard: some View {
        let metrics = viewModel.contextWindowMetrics
        let gaugeTint = Self.contextGaugeColor(for: metrics.occupancyRatio)

        return VStack(alignment: .leading, spacing: 16) {
            // 顶部小标题栏与只读标识
            HStack(spacing: 7) {
                Circle()
                    .fill(gaugeTint)
                    .frame(width: 7, height: 7)
                    .shadow(color: gaugeTint.opacity(0.55), radius: 4)

                Text("agent.usage.context_section")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                Spacer(minLength: 8)

                if viewModel.activeSessionIsReadOnly {
                    Text("agent.feishu.readonly_badge")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background(Color(uiColor: .tertiarySystemFill), in: Capsule())
                }
            }

            // 模型身份横幅（厂商图标 + 完整模型名 + 能力胶囊带）
            HStack(alignment: .top, spacing: 13) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [
                                    AppColors.primaryBlue.opacity(0.14),
                                    AppColors.primaryBlue.opacity(0.05)
                                ],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(AppColors.primaryBlue.opacity(0.20), lineWidth: 0.75)

                    AgentVendorIconView(
                        vendor: activeVendor,
                        modelName: activeModelRawName ?? viewModel.currentModelDisplayName,
                        size: 22
                    )
                }
                .frame(width: 46, height: 46)

                VStack(alignment: .leading, spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("agent.usage.context.model")
                            .font(.caption2)
                            .foregroundStyle(.secondary)

                        Text(verbatim: viewModel.currentModelDisplayName)
                            .font(.system(.headline, design: .rounded).weight(.bold))
                            .foregroundStyle(.primary)
                            .lineLimit(2)
                            .minimumScaleFactor(0.85)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    // 视觉识图、权限模式、已统计轮次三枚胶囊标签
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 6) {
                            visionCapabilityPill
                            permissionCapabilityPill
                            turnsCountPill
                        }
                        VStack(alignment: .leading, spacing: 6) {
                            HStack(spacing: 6) {
                                visionCapabilityPill
                                permissionCapabilityPill
                            }
                            turnsCountPill
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Divider().opacity(0.55)

            // 上下文窗口水位仪
            contextWindowGaugeSection(metrics: metrics, tintColor: gaugeTint)
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.16), lineWidth: 0.5)
        }
    }

    private var visionCapabilityPill: some View {
        let supported = viewModel.currentModelSupportsVision
        let tint: Color = supported
            ? Color(red: 0.13, green: 0.68, blue: 0.53)
            : .secondary

        return HStack(spacing: 4) {
            Image(systemName: supported ? "eye.fill" : "text.alignleft")
                .font(.system(size: 10, weight: .semibold))
            Text("agent.usage.context.vision")
            Text(verbatim: "·")
            Text(supported ? "agent.usage.context.vision_on" : "agent.usage.context.vision_off")
                .fontWeight(.semibold)
        }
        .font(.caption2)
        .foregroundStyle(supported ? tint : .secondary)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            supported ? tint.opacity(0.12) : Color(uiColor: .tertiarySystemFill),
            in: Capsule()
        )
    }

    private var permissionCapabilityPill: some View {
        let spec = permissionModeVisualSpec
        return HStack(spacing: 4) {
            Image(systemName: spec.symbol)
                .font(.system(size: 10, weight: .semibold))
            Text("agent.usage.context.permission")
            Text(verbatim: "·")
            Text(verbatim: spec.label)
                .fontWeight(.semibold)
        }
        .font(.caption2)
        .foregroundStyle(spec.tint)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(spec.tint.opacity(0.11), in: Capsule())
    }

    private var turnsCountPill: some View {
        HStack(spacing: 4) {
            Image(systemName: "arrow.triangle.2.circlepath")
                .font(.system(size: 10, weight: .semibold))
            Text("agent.usage.context.turns")
            Text(verbatim: "\(turnOptions.count)")
                .fontWeight(.bold)
                .monospacedDigit()
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color(uiColor: .tertiarySystemFill), in: Capsule())
    }

    private func contextWindowGaugeSection(
        metrics: AgentContextWindowMetrics,
        tintColor: Color
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 6) {
                Text("agent.usage.context.window")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.secondary)

                if metrics.isLimitEstimated, metrics.contextWindowLimit != nil {
                    Text("agent.usage.context.window_estimated")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color(uiColor: .tertiarySystemFill), in: Capsule())
                }

                Spacer(minLength: 8)

                if let pct = metrics.occupancyPercentText {
                    Text(verbatim: pct)
                        .font(.system(.caption, design: .rounded).weight(.bold).monospacedDigit())
                        .foregroundStyle(tintColor)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(tintColor.opacity(0.13), in: Capsule())
                }
            }

            HStack(alignment: .firstTextBaseline, spacing: 5) {
                if let current = metrics.currentContextTokens {
                    Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(current))
                        .font(.system(size: 24, weight: .bold, design: .rounded).monospacedDigit())
                        .foregroundStyle(.primary)

                    if let limit = metrics.contextWindowLimit {
                        Text(verbatim: "/ \(AgentTokenUsageAnalyzer.formatTokenCount(limit))")
                            .font(.subheadline.weight(.medium).monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                } else if let limit = metrics.contextWindowLimit {
                    Text(verbatim: "0")
                        .font(.system(size: 24, weight: .bold, design: .rounded).monospacedDigit())
                        .foregroundStyle(.secondary)
                    Text(verbatim: "/ \(AgentTokenUsageAnalyzer.formatTokenCount(limit))")
                        .font(.subheadline.weight(.medium).monospacedDigit())
                        .foregroundStyle(.tertiary)
                } else {
                    Text("agent.usage.context.window_unavailable")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)
            }

            // 刻度式流光水位条
            GeometryReader { geo in
                let width = geo.size.width
                let ratio = min(1.0, max(0.0, metrics.occupancyRatio ?? 0))
                let fillWidth = ratio > 0 ? max(10, width * ratio) : 0

                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color(uiColor: .tertiarySystemFill))

                    if fillWidth > 0 {
                        Capsule()
                            .fill(
                                LinearGradient(
                                    colors: [tintColor.opacity(0.72), tintColor],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .frame(width: fillWidth)
                    }

                    // 70% 与 90% 阈值刻度线
                    ForEach([0.70, 0.90], id: \.self) { marker in
                        Rectangle()
                            .fill(Color(uiColor: .systemBackground).opacity(0.65))
                            .frame(width: 1.5, height: 10)
                            .offset(x: width * marker)
                    }
                }
            }
            .frame(height: 10)
            .clipShape(Capsule())
            .accessibilityHidden(true)
        }
    }

    private var permissionModeVisualSpec: (label: String, symbol: String, tint: Color) {
        switch viewModel.permissionMode {
        case .ask:
            return (
                String(localized: "agent.permission_mode.short.ask"),
                "hand.raised.fill",
                AppColors.primaryBlue
            )
        case .autoEdit:
            return (
                String(localized: "agent.permission_mode.short.auto_edit"),
                "pencil.and.outline",
                Color.orange
            )
        case .fullAccess:
            return (
                String(localized: "agent.permission_mode.short.full_access"),
                "bolt.shield.fill",
                AppColors.overviewExpense
            )
        }
    }

    private static func contextGaugeColor(for ratio: Double?) -> Color {
        guard let ratio else { return AppColors.primaryBlue }
        if ratio >= 0.90 { return AppColors.overviewExpense }
        if ratio >= 0.70 { return Color.orange }
        return AppColors.primaryBlue
    }

    // MARK: - 模块二：交互式轮次时间轴选择器

    private var scopeTimelinePicker: some View {
        let sessionTotal = viewModel.sessionTokenSnapshot?.totalTokens ?? 0

        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                scopeTimelineChip(
                    icon: "square.stack.3d.up.fill",
                    title: String(localized: "agent.usage.scope.session"),
                    badge: sessionTotal > 0 ? AgentTokenUsageAnalyzer.formatCompactTokenCount(sessionTotal) : nil,
                    isSelected: selectedMessageId == nil
                ) {
                    selectScope(messageId: nil)
                }

                ForEach(turnOptions.reversed()) { option in
                    let turnLabel = String(localized: "agent.usage.scope.turn \(option.turnNumber)")
                    let turnTokens = option.usage.totalTokens > 0
                        ? option.usage.totalTokens
                        : (option.usage.estimatedInputTokens ?? 0)
                    let badge = turnTokens > 0
                        ? AgentTokenUsageAnalyzer.formatCompactTokenCount(turnTokens)
                        : nil

                    scopeTimelineChip(
                        icon: nil,
                        title: turnLabel,
                        badge: badge,
                        isSelected: selectedMessageId == option.messageId
                    ) {
                        selectScope(messageId: option.messageId)
                    }
                }
            }
            .padding(.horizontal, 2)
            .padding(.vertical, 2)
        }
    }

    private func selectScope(messageId: String?) {
        if reduceMotion {
            selectedMessageId = messageId
            selectedBreakdownKey = nil
        } else {
            withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                selectedMessageId = messageId
                selectedBreakdownKey = nil
            }
        }
    }

    private func scopeTimelineChip(
        icon: String?,
        title: String,
        badge: String?,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 11, weight: .semibold))
                }
                Text(verbatim: title)
                    .font(.caption.weight(isSelected ? .semibold : .medium))

                if let badge {
                    Text(verbatim: badge)
                        .font(.system(size: 10, weight: .semibold, design: .rounded).monospacedDigit())
                        .foregroundStyle(isSelected ? Color.white.opacity(0.92) : .secondary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(
                            isSelected
                                ? Color.white.opacity(0.18)
                                : Color(uiColor: .tertiarySystemFill),
                            in: Capsule()
                        )
                }
            }
            .foregroundStyle(isSelected ? .white : .primary)
            .padding(.horizontal, 13)
            .padding(.vertical, 8)
            .background(
                Group {
                    if isSelected {
                        Capsule().fill(
                            LinearGradient(
                                colors: [AppColors.primaryBlue, AppColors.primaryBluePressed],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    } else {
                        Capsule().fill(Color(uiColor: .secondarySystemGroupedBackground))
                    }
                }
            )
            .overlay {
                if !isSelected {
                    Capsule()
                        .strokeBorder(Color(uiColor: .separator).opacity(0.22), lineWidth: 0.5)
                }
            }
            .shadow(
                color: isSelected ? AppColors.primaryBlue.opacity(0.24) : .clear,
                radius: 6,
                x: 0,
                y: 3
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    // MARK: - 模块三：Token 流量与缓存效率看板

    private func usageThroughputCard(_ snapshot: AgentTokenUsageSnapshot) -> some View {
        let inputColor = AppColors.primaryBlue
        let cachedColor = Color(red: 0.13, green: 0.68, blue: 0.53)
        let outputColor = Color(red: 0.52, green: 0.38, blue: 0.94)

        return VStack(alignment: .leading, spacing: 16) {
            // 顶部标题与调用/耗时微胶囊
            HStack(alignment: .center, spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: selectedMessageId == nil ? "waveform.path.ecg" : "sparkle.magnifyingglass")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(AppColors.primaryBlue)

                    Text(selectedMessageId == nil ? "agent.usage.scope.session" : "agent.usage.scope.turn_detail")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)

                    if let turn = selectedTurnOption {
                        Text(verbatim: "#\(turn.turnNumber)")
                            .font(.caption2.weight(.bold).monospacedDigit())
                            .foregroundStyle(AppColors.primaryBlue)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(AppColors.primaryBlue.opacity(0.12), in: Capsule())
                    }
                }

                Spacer(minLength: 4)

                if snapshot.modelCallCount > 0 {
                    HStack(spacing: 6) {
                        telemetryMiniPill(
                            symbol: "bolt.fill",
                            text: "\(snapshot.reportedCallCount)/\(snapshot.modelCallCount)"
                        )
                        if snapshot.durationMillis > 0 {
                            telemetryMiniPill(
                                symbol: "timer",
                                text: AgentTokenUsageAnalyzer.formatDuration(snapshot.durationMillis)
                            )
                        }
                    }
                }
            }

            // 单轮提问上下文引用预览
            if let prompt = selectedTurnOption?.promptPreview, !prompt.isEmpty {
                HStack(alignment: .top, spacing: 10) {
                    RoundedRectangle(cornerRadius: 2, style: .continuous)
                        .fill(AppColors.primaryBlue.opacity(0.75))
                        .frame(width: 3)

                    Text(verbatim: prompt)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 2)
            }

            if snapshot.hasReportedUsage {
                // Hero 总 Token 与双向比例流光条
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .lastTextBaseline, spacing: 10) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("agent.usage.metric.total")
                                .font(.caption)
                                .foregroundStyle(.secondary)

                            Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.totalTokens))
                                .font(.system(size: 32, weight: .bold, design: .rounded).monospacedDigit())
                                .foregroundStyle(
                                    LinearGradient(
                                        colors: [AppColors.primaryBlue, AppColors.primaryBluePressed],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .lineLimit(1)
                                .minimumScaleFactor(0.75)
                        }

                        Spacer(minLength: 8)

                        if let hitRatio = snapshot.cacheHitRatio {
                            VStack(alignment: .trailing, spacing: 3) {
                                Text("agent.usage.metric.cached")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                HStack(spacing: 4) {
                                    Image(systemName: "arrow.triangle.merge")
                                        .font(.system(size: 10, weight: .bold))
                                    Text(verbatim: AgentTokenUsageAnalyzer.formatPercentage(hitRatio))
                                        .font(.system(.subheadline, design: .rounded).weight(.bold).monospacedDigit())
                                }
                                .foregroundStyle(cachedColor)
                                .padding(.horizontal, 9)
                                .padding(.vertical, 4)
                                .background(cachedColor.opacity(0.12), in: Capsule())
                            }
                        }
                    }

                    // Input vs Output 比例横条
                    GeometryReader { geo in
                        let totalWidth = max(0, geo.size.width - 3)
                        let inWidth = max(6, totalWidth * snapshot.inputShare)
                        let outWidth = max(6, totalWidth - inWidth)

                        HStack(spacing: 3) {
                            Capsule()
                                .fill(inputColor.gradient)
                                .frame(width: inWidth)
                            Capsule()
                                .fill(outputColor.gradient)
                                .frame(width: outWidth)
                        }
                    }
                    .frame(height: 7)
                    .accessibilityHidden(true)
                }

                Divider().opacity(0.5)

                // 三列通透指标舱（输入 / 缓存输入 / 输出）
                HStack(alignment: .top, spacing: 12) {
                    trioMetricColumn(
                        accentColor: inputColor,
                        title: "agent.usage.metric.input",
                        value: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.inputTokens),
                        badgeText: AgentTokenUsageAnalyzer.formatPercentage(snapshot.inputShare)
                    )

                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.32))
                        .frame(width: 0.5, height: 54)
                        .padding(.top, 4)
                        .accessibilityHidden(true)

                    trioMetricColumn(
                        accentColor: cachedColor,
                        title: "agent.usage.metric.cached",
                        value: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.cachedInputTokens),
                        badgeText: snapshot.cacheHitRatio.map { AgentTokenUsageAnalyzer.formatPercentage($0) }
                    )

                    Rectangle()
                        .fill(Color(uiColor: .separator).opacity(0.32))
                        .frame(width: 0.5, height: 54)
                        .padding(.top, 4)
                        .accessibilityHidden(true)

                    trioMetricColumn(
                        accentColor: outputColor,
                        title: "agent.usage.metric.output",
                        value: AgentTokenUsageAnalyzer.formatTokenCount(snapshot.outputTokens),
                        badgeText: AgentTokenUsageAnalyzer.formatPercentage(snapshot.outputShare)
                    )
                }

                // 底部调用次数与模型耗时双栏条
                HStack(spacing: 12) {
                    footerStatPill(
                        symbol: "checkmark.seal.fill",
                        title: "agent.usage.metric.reported_calls",
                        value: "\(snapshot.reportedCallCount) / \(snapshot.modelCallCount)"
                    )
                    footerStatPill(
                        symbol: "clock.fill",
                        title: "agent.usage.metric.duration",
                        value: AgentTokenUsageAnalyzer.formatDuration(snapshot.durationMillis)
                    )
                }
            } else {
                HStack(spacing: 10) {
                    Image(systemName: "waveform.slash")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("agent.usage.provider_unavailable")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
            }

            if snapshot.status == .partial {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.caption)
                        .foregroundStyle(Color.orange)
                        .padding(.top, 1)
                    Text("agent.usage.partial_notice")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(11)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    Color.orange.opacity(0.09),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
            }
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.16), lineWidth: 0.5)
        }
    }

    private func telemetryMiniPill(symbol: String, text: String) -> some View {
        HStack(spacing: 3.5) {
            Image(systemName: symbol)
                .font(.system(size: 9, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(verbatim: text)
                .font(.caption2.weight(.medium).monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color(uiColor: .tertiarySystemFill), in: Capsule())
    }

    private func trioMetricColumn(
        accentColor: Color,
        title: LocalizedStringKey,
        value: String,
        badgeText: String?
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 5) {
                Capsule()
                    .fill(accentColor)
                    .frame(width: 16, height: 3)

                if let badgeText {
                    Text(verbatim: badgeText)
                        .font(.system(size: 10, weight: .semibold, design: .rounded).monospacedDigit())
                        .foregroundStyle(accentColor)
                }
            }

            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)

            Text(verbatim: value)
                .font(.system(.subheadline, design: .rounded).weight(.bold).monospacedDigit())
                .foregroundStyle(.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func footerStatPill(
        symbol: String,
        title: LocalizedStringKey,
        value: String
    ) -> some View {
        HStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.caption)
                .foregroundStyle(AppColors.primaryBlue.opacity(0.85))
                .frame(width: 24, height: 24)
                .background(AppColors.primaryBlue.opacity(0.10), in: Circle())

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(verbatim: value)
                    .font(.caption.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.primary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color(uiColor: .tertiarySystemGroupedBackground).opacity(0.75),
            in: RoundedRectangle(cornerRadius: 13, style: .continuous)
        )
    }

    // MARK: - 模块四：交互式输入构成光谱与环形图

    private func breakdownSpectrumCard(_ snapshot: AgentTokenUsageSnapshot) -> some View {
        let segments = AgentTokenUsageAnalyzer.breakdownSegments(from: snapshot.breakdown)
        let total = segments.reduce(Int64(0)) { $0 + $1.tokens }
        let activeSegment = segments.first { $0.key == selectedBreakdownKey }

        return VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 7) {
                Image(systemName: "chart.pie.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.primaryBlue)
                Text("agent.usage.breakdown.title")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Spacer(minLength: 4)
                if total > 0 {
                    Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(total))
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }

            if !segments.isEmpty {
                // 环形图与中心聚焦读数
                ZStack {
                    Chart(segments) { segment in
                        let isHighlighted = selectedBreakdownKey == segment.key
                        SectorMark(
                            angle: .value("Tokens", segment.tokens),
                            innerRadius: .ratio(0.66),
                            outerRadius: .inset(isHighlighted ? 2 : 8),
                            angularInset: 1.8
                        )
                        .cornerRadius(5)
                        .foregroundStyle(Self.color(for: segment.key))
                        .opacity(selectedBreakdownKey == nil || isHighlighted ? 1.0 : 0.28)
                    }
                    .frame(height: 196)

                    VStack(spacing: 4) {
                        if let activeSegment {
                            Image(systemName: activeSegment.key.symbolName)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Self.color(for: activeSegment.key))
                        }

                        Text(activeSegment.map { LocalizedStringKey($0.key.localizedTitleKey) }
                            ?? LocalizedStringKey("agent.usage.breakdown.input_total"))
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)

                        Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(activeSegment?.tokens ?? total))
                            .font(.system(.title3, design: .rounded).weight(.bold).monospacedDigit())
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)

                        if let activeSegment {
                            Text(verbatim: AgentTokenUsageAnalyzer.formatPercentage(activeSegment.share))
                                .font(.caption2.weight(.bold).monospacedDigit())
                                .foregroundStyle(Self.color(for: activeSegment.key))
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(Self.color(for: activeSegment.key).opacity(0.13), in: Capsule())
                        }
                    }
                    .frame(maxWidth: 118)
                    .multilineTextAlignment(.center)
                }

                // 带微光图标方舱与内联比例条的维度列表
                VStack(spacing: 8) {
                    ForEach(segments) { segment in
                        breakdownSegmentRow(segment)
                    }
                }
            }

            HStack(alignment: .top, spacing: 6) {
                Image(systemName: "sparkle")
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
                    .padding(.top, 2)
                Text("agent.usage.breakdown.note")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.16), lineWidth: 0.5)
        }
    }

    private func breakdownSegmentRow(_ segment: AgentTokenBreakdownSegment) -> some View {
        let isSelected = selectedBreakdownKey == segment.key
        let color = Self.color(for: segment.key)

        return Button {
            if reduceMotion {
                selectedBreakdownKey = isSelected ? nil : segment.key
            } else {
                withAnimation(.easeInOut(duration: 0.18)) {
                    selectedBreakdownKey = isSelected ? nil : segment.key
                }
            }
        } label: {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(color.opacity(isSelected ? 0.22 : 0.13))
                    Image(systemName: segment.key.symbolName)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(color)
                }
                .frame(width: 32, height: 32)

                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(LocalizedStringKey(segment.key.localizedTitleKey))
                            .font(.subheadline.weight(isSelected ? .semibold : .regular))
                            .foregroundStyle(.primary)
                            .lineLimit(1)

                        Spacer(minLength: 6)

                        Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(segment.tokens))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)

                        Text(verbatim: AgentTokenUsageAnalyzer.formatPercentage(segment.share))
                            .font(.caption.weight(.bold).monospacedDigit())
                            .foregroundStyle(isSelected ? color : .primary)
                            .frame(minWidth: 42, alignment: .trailing)
                    }

                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule()
                                .fill(color.opacity(0.10))
                            Capsule()
                                .fill(color.gradient)
                                .frame(width: max(4, geo.size.width * min(1.0, max(0.0, segment.share))))
                        }
                    }
                    .frame(height: 5)
                    .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(
                isSelected
                    ? color.opacity(0.10)
                    : Color(uiColor: .tertiarySystemGroupedBackground).opacity(0.45),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .overlay {
                if isSelected {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(color.opacity(0.35), lineWidth: 1)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    // MARK: - 模块五：工具开销与模型调用检查器

    private func inspectorDisclosureCard(_ snapshot: AgentTokenUsageSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            if !snapshot.toolUsages.isEmpty {
                DisclosureGroup(isExpanded: $showTools) {
                    VStack(spacing: 8) {
                        ForEach(snapshot.toolUsages) { tool in
                            toolUsageRow(tool)
                        }
                    }
                    .padding(.top, 10)
                } label: {
                    inspectorHeaderLabel(
                        symbol: "wrench.and.screwdriver.fill",
                        tint: Color(red: 0.95, green: 0.62, blue: 0.22),
                        title: "agent.usage.tools_title",
                        count: snapshot.toolUsages.count
                    )
                }
            }

            if !snapshot.toolUsages.isEmpty && !snapshot.calls.isEmpty {
                Divider().opacity(0.55)
            }

            if !snapshot.calls.isEmpty {
                DisclosureGroup(isExpanded: $showCalls) {
                    VStack(spacing: 8) {
                        ForEach(Array(snapshot.calls.enumerated()), id: \.offset) { idx, call in
                            modelCallRow(index: idx + 1, call: call)
                        }
                    }
                    .padding(.top, 10)
                } label: {
                    inspectorHeaderLabel(
                        symbol: "cpu.fill",
                        tint: AppColors.primaryBlue,
                        title: "agent.usage.calls_title",
                        count: snapshot.calls.count
                    )
                }
            }
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.16), lineWidth: 0.5)
        }
    }

    private func inspectorHeaderLabel(
        symbol: String,
        tint: Color,
        title: LocalizedStringKey,
        count: Int
    ) -> some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(tint.opacity(0.14))
                Image(systemName: symbol)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(tint)
            }
            .frame(width: 28, height: 28)

            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)

            Spacer(minLength: 8)

            Text(verbatim: "\(count)")
                .font(.caption.weight(.bold).monospacedDigit())
                .foregroundStyle(tint)
                .padding(.horizontal, 9)
                .padding(.vertical, 3)
                .background(tint.opacity(0.12), in: Capsule())
        }
    }

    private func toolUsageRow(_ tool: AgentToolTokenUsage) -> some View {
        let total = max(0, tool.schemaTokens) + max(0, tool.resultTokens)
        let schemaShare = total > 0 ? Double(max(0, tool.schemaTokens)) / Double(total) : 0.5
        let schemaColor = Self.color(for: .toolSchema)
        let resultColor = Self.color(for: .toolResult)

        return VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: tool.toolName)
                    .font(.system(.caption, design: .monospaced).weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Spacer(minLength: 8)

                Text(verbatim: AgentTokenUsageAnalyzer.formatTokenCount(total))
                    .font(.caption.weight(.bold).monospacedDigit())
                    .foregroundStyle(.primary)
            }

            HStack(spacing: 10) {
                HStack(spacing: 4) {
                    Circle().fill(schemaColor).frame(width: 6, height: 6)
                    Text(verbatim: "Schema \(AgentTokenUsageAnalyzer.formatTokenCount(tool.schemaTokens))")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                HStack(spacing: 4) {
                    Circle().fill(resultColor).frame(width: 6, height: 6)
                    Text(verbatim: "Result \(AgentTokenUsageAnalyzer.formatTokenCount(tool.resultTokens))")
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)
            }

            if total > 0 {
                GeometryReader { geo in
                    let w = max(0, geo.size.width - 2)
                    let sW = max(3, w * schemaShare)
                    let rW = max(3, w - sW)
                    HStack(spacing: 2) {
                        Capsule().fill(schemaColor).frame(width: sW)
                        Capsule().fill(resultColor).frame(width: rW)
                    }
                }
                .frame(height: 4)
                .accessibilityHidden(true)
            }
        }
        .padding(11)
        .background(
            Color(uiColor: .tertiarySystemGroupedBackground).opacity(0.65),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
    }

    private func modelCallRow(index: Int, call: AgentModelInvocationUsage) -> some View {
        let inStr = call.inputTokens.map { AgentTokenUsageAnalyzer.formatTokenCount($0) } ?? "—"
        let outStr = call.outputTokens.map { AgentTokenUsageAnalyzer.formatTokenCount($0) } ?? "—"
        let cachedStr = call.cachedInputTokens.map { AgentTokenUsageAnalyzer.formatTokenCount($0) } ?? "—"

        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(verbatim: "#\(index)")
                    .font(.caption2.weight(.bold).monospacedDigit())
                    .foregroundStyle(AppColors.primaryBlue)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(AppColors.primaryBlue.opacity(0.12), in: Capsule())

                Text(verbatim: call.model ?? "-")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)

                Spacer(minLength: 8)

                HStack(spacing: 3) {
                    Image(systemName: "timer")
                        .font(.system(size: 9))
                    Text(verbatim: AgentTokenUsageAnalyzer.formatDuration(call.durationMillis ?? 0))
                        .font(.caption2.weight(.medium).monospacedDigit())
                }
                .foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                callMetricBadge(prefix: "In", value: inStr, tint: AppColors.primaryBlue)
                callMetricBadge(prefix: "Out", value: outStr, tint: Color(red: 0.52, green: 0.38, blue: 0.94))
                callMetricBadge(prefix: "Cached", value: cachedStr, tint: Color(red: 0.13, green: 0.68, blue: 0.53))
                Spacer(minLength: 0)
            }
        }
        .padding(11)
        .background(
            Color(uiColor: .tertiarySystemGroupedBackground).opacity(0.65),
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
    }

    private func callMetricBadge(prefix: String, value: String, tint: Color) -> some View {
        HStack(spacing: 4) {
            Text(verbatim: prefix)
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(tint)
            Text(verbatim: value)
                .font(.caption2.weight(.medium).monospacedDigit())
                .foregroundStyle(.primary)
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .background(tint.opacity(0.09), in: Capsule())
    }

    // MARK: - 空状态

    private var emptyUsageCard: some View {
        HStack(alignment: .top, spacing: 14) {
            ZStack {
                Circle()
                    .fill(AppColors.primaryBlue.opacity(0.12))
                Image(systemName: "chart.bar.doc.horizontal")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.primaryBlue)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 5) {
                Text("agent.usage.empty_title")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text("agent.usage.empty_desc")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
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
