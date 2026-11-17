import SwiftUI

/// 极简对话工作区：沿用系统纯净画布与留白层级，无模块化卡片框线。
struct AgentChatView: View {
    @ObservedObject var viewModel: AgentChatViewModel
    @Environment(\.locale) private var locale

    @State private var showSessionsSheet = false
    @State private var showModelPicker = false
    @State private var showPlanSheet = false
    @State private var showRenameAlert = false
    @State private var renameDraft = ""
    @FocusState private var isInputFocused: Bool

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
        .sheet(isPresented: $showModelPicker) {
            AgentModelPickerSheet(viewModel: viewModel) {
                showModelPicker = false
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
            if viewModel.activePlanContent != nil {
                Button {
                    showPlanSheet = true
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
                        LazyVStack(alignment: .leading, spacing: 22) {
                            ForEach(viewModel.messages) { message in
                                AgentMessageRow(
                                    message: message,
                                    elapsedSeconds: message.isStreaming ? viewModel.elapsedSeconds : message.durationSeconds
                                )
                                .id(message.id)
                            }

                            if let notice = viewModel.routingNotice {
                                Text(verbatim: notice.message)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }

                            if let approval = viewModel.pendingApproval {
                                AgentApprovalCardView(
                                    approval: approval,
                                    selections: $viewModel.approvalSelections,
                                    isSubmitting: viewModel.isSubmittingApproval
                                ) { overrideAll in
                                    Task { await viewModel.submitApprovalDecisions(overrideAllApproved: overrideAll) }
                                }
                                .id("pending_approval_card")
                            }

                            Color.clear
                                .frame(height: 8)
                                .id("bottom_anchor")
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 12)
                        .padding(.bottom, 16)
                    }
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .onTapGesture {
                isInputFocused = false
            }
            .onChange(of: viewModel.messages.count) { _, _ in
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo("bottom_anchor", anchor: .bottom)
                }
            }
            .onChange(of: viewModel.isLoadingMessages) { _, loading in
                if !loading && !viewModel.messages.isEmpty {
                    proxy.scrollTo("bottom_anchor", anchor: .bottom)
                }
            }
            .onChange(of: viewModel.messages.last?.content) { _, _ in
                if viewModel.isStreaming {
                    proxy.scrollTo("bottom_anchor", anchor: .bottom)
                }
            }
            .onChange(of: viewModel.pendingApproval?.approvalId) { _, newValue in
                if newValue != nil {
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo("pending_approval_card", anchor: .bottom)
                    }
                }
            }
        }
    }

    private var emptyConversationState: some View {
        VStack(spacing: 24) {
            Spacer(minLength: 100)

            VStack(spacing: 8) {
                Text("agent.empty.title")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.center)
                Text("agent.empty.subtitle")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 32)

            VStack(spacing: 10) {
                quickPromptChip("agent.prompt.today_summary", keyName: "agent.prompt.today_summary")
                quickPromptChip("agent.prompt.expense_check", keyName: "agent.prompt.expense_check")
                quickPromptChip("agent.prompt.study_plan", keyName: "agent.prompt.study_plan")
            }
            .padding(.horizontal, 28)

            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func quickPromptChip(_ title: LocalizedStringKey, keyName: String.LocalizationValue) -> some View {
        let resolved = AppLocalization.string(keyName, locale: locale)
        Button {
            Task {
                await viewModel.sendPrompt(resolved)
            }
        } label: {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Color(uiColor: .secondarySystemBackground), in: Capsule())
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
                    showModelPicker = true
                } label: {
                    HStack(spacing: 4) {
                        Text(verbatim: viewModel.currentModelDisplayName)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(uiColor: .tertiarySystemFill).opacity(0.6), in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("agent.model.picker_title")

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

    var body: some View {
        if message.role == .user {
            HStack {
                Spacer(minLength: 48)
                Text(verbatim: message.content)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .textSelection(.enabled)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)
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
            } else if message.isStreaming && split.finalText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
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

            if !split.finalText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                AgentMarkdownView(text: split.finalText)
                    .font(.body)
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
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
