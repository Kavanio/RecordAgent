import SwiftUI

/// 极简对话工作区：沿用系统纯净画布与留白层级，无模块化卡片框线。
struct AgentChatView: View {
    @ObservedObject var viewModel: AgentChatViewModel
    @Environment(\.locale) private var locale

    @State private var showSessionsSheet = false
    @State private var showModelPopover = false
    @State private var showPlanSheet = false
    @State private var showRenameAlert = false
    @State private var renameDraft = ""
    @State private var isAutoScrollEnabled = true
    @State private var isBottomVisible = true
    @State private var sendFeedbackTrigger = false
    @FocusState private var isInputFocused: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: 0) {
            topBar

            messageArea

            if let errorMessage = viewModel.errorMessage, !errorMessage.isEmpty {
                errorBanner(errorMessage)
            }

            if viewModel.activeSessionIsReadOnly {
                readOnlyNoticeBar
            } else {
                composerBar
            }
        }
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .task {
            await viewModel.loadInitialIfNeeded()
        }
        .sheet(isPresented: $showSessionsSheet) {
            AgentSessionsSheet(viewModel: viewModel) {
                showSessionsSheet = false
            }
        }
        .sheet(isPresented: $showPlanSheet) {
            if let plan = viewModel.activePlanContent {
                AgentPlanSheet(content: plan) {
                    showPlanSheet = false
                }
            }
        }
        .alert("agent.session.rename_title", isPresented: $showRenameAlert) {
            TextField("agent.session.rename_placeholder", text: $renameDraft)
            Button("common.cancel", role: .cancel) {}
            Button("common.confirm") {
                if let current = viewModel.activeSession {
                    Task { await viewModel.renameSession(current, newTitle: renameDraft) }
                }
            }
        }
    }

    // MARK: - 顶部极简导航栏

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                isInputFocused = false
                showSessionsSheet = true
            } label: {
                Image(systemName: "line.3.horizontal")
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("agent.sessions.title")

            Spacer(minLength: 4)

            if let session = viewModel.activeSession, !viewModel.messages.isEmpty {
                HStack(spacing: 6) {
                    Text(verbatim: session.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if viewModel.activeSessionIsReadOnly {
                        Text("agent.feishu.readonly_badge")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color(uiColor: .secondarySystemBackground), in: Capsule())
                    }
                }
            }

            Spacer(minLength: 4)

            HStack(spacing: 0) {
                if viewModel.activeSession != nil && !viewModel.activeSessionIsReadOnly {
                    sessionOptionsMenu
                }

                Button {
                    isInputFocused = false
                    viewModel.startNewChat()
                } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("agent.new_chat")
            }
        }
        .padding(.horizontal, 10)
        .padding(.top, 2)
        .padding(.bottom, 4)
    }

    private var sessionOptionsMenu: some View {
        Menu {
            if viewModel.currentSessionHasPlan {
                Button {
                    Task {
                        if viewModel.activePlanContent == nil {
                            await viewModel.loadPlan()
                        }
                        if viewModel.activePlanContent != nil {
                            showPlanSheet = true
                        }
                    }
                } label: {
                    Label("agent.plan.title", systemImage: "list.bullet.rectangle")
                }
            }

            if let current = viewModel.activeSession {
                Button {
                    renameDraft = current.title
                    showRenameAlert = true
                } label: {
                    Label("agent.session.rename", systemImage: "pencil")
                }

                Button(role: .destructive) {
                    Task { await viewModel.deleteSession(current) }
                } label: {
                    Label("agent.session.delete", systemImage: "trash")
                }
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.medium))
                .foregroundStyle(.primary)
                .frame(width: 40, height: 44)
                .contentShape(Rectangle())
        }
        .accessibilityLabel("agent.session.options")
    }

    private func permissionModeLabel(_ mode: AgentSessionPermissionMode) -> LocalizedStringKey {
        switch mode {
        case .ask: return "agent.permission_mode.ask"
        case .autoEdit: return "agent.permission_mode.auto_edit"
        case .fullAccess: return "agent.permission_mode.full_access"
        }
    }

    private func permissionModeShortLabel(_ mode: AgentSessionPermissionMode) -> LocalizedStringKey {
        switch mode {
        case .ask: return "agent.permission_mode.short.ask"
        case .autoEdit: return "agent.permission_mode.short.auto_edit"
        case .fullAccess: return "agent.permission_mode.short.full_access"
        }
    }

    private func permissionModeIcon(_ mode: AgentSessionPermissionMode) -> String {
        switch mode {
        case .ask: return "hand.raised"
        case .autoEdit: return "pencil.line"
        case .fullAccess: return "bolt"
        }
    }

    // MARK: - 对话内容流

    private var messageArea: some View {
        ScrollViewReader { proxy in
            ZStack(alignment: .bottom) {
                ScrollView {
                    Group {
                        if viewModel.isLoadingInitial || (viewModel.isLoadingMessages && viewModel.messages.isEmpty) {
                            VStack(spacing: 12) {
                                Spacer(minLength: 160)
                                ProgressView()
                                Spacer()
                            }
                            .frame(maxWidth: .infinity)
                        } else if viewModel.messages.isEmpty {
                            emptyConversationState
                        } else {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                ForEach(Array(viewModel.messages.enumerated()), id: \.element.id) { idx, message in
                                    let topGap = messageTopSpacing(at: idx)
                                    AgentMessageRow(
                                        message: message,
                                        elapsedSeconds: message.isStreaming ? viewModel.elapsedSeconds : message.durationSeconds
                                    )
                                    .padding(.top, topGap)
                                    .id(message.id)
                                }

                                if let notice = viewModel.routingNotice {
                                    Text(verbatim: notice.message)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .padding(.top, 12)
                                }

                                if let approval = viewModel.pendingApproval {
                                    AgentApprovalCardView(
                                        approval: approval,
                                        selections: $viewModel.approvalSelections,
                                        isSubmitting: viewModel.isSubmittingApproval,
                                        onViewPlan: {
                                            Task {
                                                if viewModel.activePlanContent == nil {
                                                    await viewModel.loadPlan()
                                                }
                                                if viewModel.activePlanContent != nil {
                                                    showPlanSheet = true
                                                }
                                            }
                                        }
                                    ) { overrideAll in
                                        Task { await viewModel.submitApprovalDecisions(overrideAllApproved: overrideAll) }
                                    }
                                    .padding(.top, 16)
                                    .id("pending_approval_card")
                                }

                                Color.clear
                                    .frame(height: 1)
                                    .padding(.top, 10)
                                    .id("bottom_anchor")
                                    .onAppear {
                                        isBottomVisible = true
                                    }
                                    .onDisappear {
                                        isBottomVisible = false
                                    }
                            }
                            .padding(.horizontal, 18)
                            .padding(.top, 12)
                            .padding(.bottom, 16)
                        }
                    }
                }
                .scrollDismissesKeyboard(.interactively)
                .simultaneousGesture(
                    DragGesture(minimumDistance: 12)
                        .onChanged { value in
                            // 用户手指向下滑（即向上回看历史消息）时立即停止自动抢滚
                            if value.translation.height > 10 {
                                isAutoScrollEnabled = false
                            }
                        }
                )
                .onTapGesture {
                    isInputFocused = false
                }
                .onChange(of: viewModel.messages.count) { oldCount, newCount in
                    if newCount > oldCount {
                        isAutoScrollEnabled = true
                    }
                    scrollToBottom(proxy: proxy, animated: !reduceMotion)
                }
                .onChange(of: viewModel.isLoadingMessages) { _, loading in
                    if !loading && !viewModel.messages.isEmpty {
                        isAutoScrollEnabled = true
                        scrollToBottom(proxy: proxy, animated: false)
                    }
                }
                .onChange(of: viewModel.messages.last?.content) { _, _ in
                    if viewModel.isStreaming && isAutoScrollEnabled {
                        scrollToBottom(proxy: proxy, animated: false)
                    }
                }
                .onChange(of: viewModel.pendingApproval?.approvalId) { _, newValue in
                    if newValue != nil {
                        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                            proxy.scrollTo("pending_approval_card", anchor: .bottom)
                        }
                    }
                }

                if !viewModel.messages.isEmpty && !isBottomVisible {
                    Button {
                        isAutoScrollEnabled = true
                        scrollToBottom(proxy: proxy, animated: !reduceMotion)
                    } label: {
                        Image(systemName: "arrow.down")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .frame(width: 36, height: 36)
                            .background(.regularMaterial, in: Circle())
                            .overlay {
                                Circle()
                                    .strokeBorder(Color(uiColor: .separator).opacity(0.3), lineWidth: 0.5)
                            }
                            .shadow(color: Color.black.opacity(0.08), radius: 8, x: 0, y: 3)
                    }
                    .buttonStyle(.plain)
                    .padding(.bottom, 12)
                    .accessibilityLabel("agent.scroll_to_bottom")
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.85).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.18), value: isBottomVisible)
        }
    }

    private func messageTopSpacing(at index: Int) -> CGFloat {
        guard index > 0 else { return 0 }
        let previous = viewModel.messages[index - 1]
        let current = viewModel.messages[index]
        // 同一轮对话内（用户提问 -> 助手回答）保持紧凑关联，不同轮次之间明显拉开呼吸空间
        if previous.role == .user && current.role == .assistant {
            return 16
        }
        return 26
    }

    private func scrollToBottom(proxy: ScrollViewProxy, animated: Bool) {
        if animated {
            withAnimation(.easeOut(duration: 0.22)) {
                proxy.scrollTo("bottom_anchor", anchor: .bottom)
            }
        } else {
            proxy.scrollTo("bottom_anchor", anchor: .bottom)
        }
    }

    private var emptyConversationState: some View {
        VStack(spacing: 28) {
            Spacer(minLength: 96)

            VStack(spacing: 10) {
                Text("agent.empty.title")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.center)
                Text("agent.empty.subtitle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineSpacing(4)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 32)

            VStack(spacing: 10) {
                quickPromptChip("agent.prompt.today_summary", keyName: "agent.prompt.today_summary", icon: "sun.max")
                quickPromptChip("agent.prompt.expense_check", keyName: "agent.prompt.expense_check", icon: "chart.bar.xaxis")
                quickPromptChip("agent.prompt.study_plan", keyName: "agent.prompt.study_plan", icon: "book")
            }
            .padding(.horizontal, 28)

            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func quickPromptChip(
        _ title: LocalizedStringKey,
        keyName: String.LocalizationValue,
        icon: String
    ) -> some View {
        let resolved = AppLocalization.string(keyName, locale: locale)
        Button {
            sendFeedbackTrigger.toggle()
            Task {
                await viewModel.sendPrompt(resolved)
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.primaryBlue)
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(Color(uiColor: .secondarySystemBackground), in: Capsule())
            .overlay {
                Capsule()
                    .strokeBorder(Color(uiColor: .separator).opacity(0.2), lineWidth: 0.5)
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: - 底部一体式极简胶囊输入区（含模型与权限控制）

    private var composerBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("agent.input.placeholder", text: $viewModel.draftText, axis: .vertical)
                .font(.body)
                .lineLimit(1...6)
                .focused($isInputFocused)
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 2)
                .disabled(viewModel.activeSessionIsReadOnly)

            HStack(spacing: 8) {
                Button {
                    isInputFocused = false
                    Task { await viewModel.refreshModelState() }
                    showModelPopover = true
                } label: {
                    HStack(spacing: 5) {
                        if viewModel.isSwitchingModel {
                            ProgressView()
                                .controlSize(.mini)
                        } else {
                            AgentVendorIconView(
                                vendor: viewModel.modelConfig?.vendor ?? viewModel.fullModelConfig?.activeVendor,
                                modelName: viewModel.modelConfig?.name ?? viewModel.fullModelConfig?.activeModel,
                                size: 14
                            )
                        }
                        Text(verbatim: viewModel.currentModelDisplayName)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 8.5, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(uiColor: .tertiarySystemFill).opacity(0.6), in: Capsule())
                }
                .buttonStyle(.plain)
                .disabled(viewModel.isSwitchingModel)
                .accessibilityLabel("agent.model.picker_title")
                .popover(
                    isPresented: $showModelPopover,
                    attachmentAnchor: .rect(.bounds),
                    arrowEdge: .bottom
                ) {
                    modelPopoverContent
                        .presentationCompactAdaptation(.popover)
                }

                Menu {
                    ForEach(AgentSessionPermissionMode.allCases, id: \.self) { mode in
                        Button {
                            Task { await viewModel.updatePermissionMode(mode) }
                        } label: {
                            if viewModel.permissionMode == mode {
                                Label(permissionModeLabel(mode), systemImage: "checkmark")
                            } else {
                                Label(permissionModeLabel(mode), systemImage: permissionModeIcon(mode))
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: permissionModeIcon(viewModel.permissionMode))
                            .font(.system(size: 11, weight: .medium))
                            .foregroundStyle(viewModel.permissionMode == .fullAccess ? AppColors.overviewExpense : .secondary)
                        Text(permissionModeShortLabel(viewModel.permissionMode))
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(uiColor: .tertiarySystemFill).opacity(0.6), in: Capsule())
                }
                .accessibilityLabel("agent.permission_mode.title")

                Spacer(minLength: 4)

                if viewModel.isStreaming {
                    Button {
                        Task { await viewModel.stopStreaming() }
                    } label: {
                        Image(systemName: viewModel.isStopping ? "hourglass" : "stop.fill")
                            .font(.footnote.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 32, height: 32)
                            .background(Color.primary, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .disabled(viewModel.isStopping)
                    .accessibilityLabel("agent.stop_generation")
                } else {
                    Button {
                        isInputFocused = false
                        sendFeedbackTrigger.toggle()
                        isAutoScrollEnabled = true
                        Task { await viewModel.sendCurrentDraft() }
                    } label: {
                        Image(systemName: "arrow.up")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(viewModel.canSend ? .white : Color(uiColor: .tertiaryLabel))
                            .frame(width: 32, height: 32)
                            .background(
                                viewModel.canSend ? AppColors.primaryBlue : Color(uiColor: .tertiarySystemFill),
                                in: Circle()
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(!viewModel.canSend)
                    .sensoryFeedback(.impact(flexibility: .soft, intensity: 0.7), trigger: sendFeedbackTrigger)
                    .accessibilityLabel("agent.send")
                }
            }
            .padding(.horizontal, 10)
            .padding(.bottom, 9)
        }
        .background(
            Color(uiColor: .secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(Color(uiColor: .separator).opacity(0.22), lineWidth: 0.5)
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .padding(.bottom, 10)
    }

    private var modelPopoverContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 2) {
                if viewModel.flattenedModels.isEmpty {
                    Text("agent.model.empty_title")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(viewModel.flattenedModels) { option in
                        let isActive = viewModel.isCurrentModel(provider: option.provider, item: option.item)
                        Button {
                            showModelPopover = false
                            Task {
                                _ = await viewModel.switchModel(provider: option.provider, item: option.item)
                            }
                        } label: {
                            HStack(spacing: 10) {
                                AgentVendorIconView(
                                    vendor: option.provider.type,
                                    modelName: option.item.modelName,
                                    size: 18
                                )
                                Text(verbatim: option.item.displayTitle)
                                    .font(.subheadline.weight(isActive ? .semibold : .regular))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                Spacer(minLength: 12)
                                if isActive {
                                    Image(systemName: "checkmark")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(AppColors.primaryBlue)
                                }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .background(
                                isActive ? AppColors.primaryBlue.opacity(0.08) : Color.clear,
                                in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                            )
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(6)
        }
        .frame(minWidth: 230, maxWidth: 290, maxHeight: 320)
        .background(Color(uiColor: .systemBackground))
    }

    private var readOnlyNoticeBar: some View {
        Text("agent.feishu.readonly_hint")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .padding(.horizontal, 16)
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.circle")
                .font(.caption)
                .foregroundStyle(AppColors.overviewExpense)
            Text(verbatim: message)
                .font(.caption)
                .foregroundStyle(AppColors.overviewExpense)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            Button {
                viewModel.errorMessage = nil
            } label: {
                Image(systemName: "xmark")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 24, height: 24)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 6)
    }
}

/// 单条对话行：用户消息为柔和胶囊气泡，助手消息直排在纯白/深色画布上。
private struct AgentMessageRow: View {
    let message: AgentChatMessage
    let elapsedSeconds: Int?
    @State private var copied = false

    var body: some View {
        if message.role == .user {
            HStack {
                Spacer(minLength: 48)
                Text(verbatim: message.content)
                    .font(.callout)
                    .lineSpacing(4.5)
                    .foregroundStyle(.primary)
                    .textSelection(.enabled)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(
                        Color(uiColor: .secondarySystemBackground),
                        in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                    )
            }
        } else {
            assistantBody
        }
    }

    private var assistantBody: some View {
        let split = AgentTimelineBuilder.splitProcessAndFinalBlocks(for: message)
        let trimmedFinal = split.finalText.trimmingCharacters(in: .whitespacesAndNewlines)
        return VStack(alignment: .leading, spacing: 10) {
            if message.proactive {
                Text("agent.proactive.badge")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
            }

            if split.hasProcessTrace {
                AgentProcessTimelineView(
                    blocks: split.processBlocks,
                    isStreaming: message.isStreaming,
                    elapsedSeconds: elapsedSeconds
                )
            } else if message.isStreaming && trimmedFinal.isEmpty {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.mini)
                        .tint(AppColors.primaryBlue)
                    Text("agent.thinking.in_progress")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            if !trimmedFinal.isEmpty {
                AgentMarkdownView(text: split.finalText)
                    .foregroundStyle(.primary)
            }

            if message.status == .cancelled {
                Text("agent.status.cancelled")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else if message.status == .failed && split.finalText.isEmpty {
                Text("agent.status.failed")
                    .font(.caption)
                    .foregroundStyle(AppColors.overviewExpense)
            }

            if !message.isStreaming && !trimmedFinal.isEmpty {
                HStack(spacing: 14) {
                    Button {
                        UIPasteboard.general.string = AgentInlineMarkdownParser.stripMarkupTagsOnly(trimmedFinal)
                        copied = true
                        Task {
                            try? await Task.sleep(for: .seconds(1.5))
                            copied = false
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: copied ? "checkmark" : "doc.on.doc")
                                .font(.caption.weight(.medium))
                            if copied {
                                Text("agent.copied")
                                    .font(.caption2.weight(.medium))
                            }
                        }
                        .foregroundStyle(copied ? AppColors.primaryBlue : .secondary)
                        .frame(minHeight: 28)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .sensoryFeedback(.impact(flexibility: .soft, intensity: 0.6), trigger: copied)
                    .accessibilityLabel(copied ? LocalizedStringKey("agent.copied") : LocalizedStringKey("agent.copy_response"))
                }
                .padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
