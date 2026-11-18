import PhotosUI
import SwiftUI

/// 极简对话工作区：沿用系统纯净画布与留白层级，无模块化卡片框线。
struct AgentChatView: View {
    @ObservedObject var viewModel: AgentChatViewModel
    var onOpenSessionsDrawer: (() -> Void)? = nil
    @Environment(\.locale) private var locale

    @State private var showModelPopover = false
    @State private var showPlanSheet = false
    @State private var showTokenUsageSheet = false
    @State private var tokenUsageInitialMessageId: String?
    @State private var selectedCitation: AgentCitedReference?
    @State private var previewImageItem: AgentChatImageAttachment?
    @State private var showPhotosPicker = false
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var showCameraSheet = false
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
                .overlay(alignment: .bottom) {
                    composerFloatingOverlay
                }

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
        .sheet(isPresented: $showPlanSheet) {
            if let plan = viewModel.activePlanContent {
                AgentPlanSheet(content: plan) {
                    showPlanSheet = false
                }
            }
        }
        .sheet(isPresented: $showTokenUsageSheet) {
            AgentTokenUsageSheet(
                viewModel: viewModel,
                initialSelectedMessageId: tokenUsageInitialMessageId
            ) {
                showTokenUsageSheet = false
            }
        }
        .sheet(item: $selectedCitation) { citation in
            AgentReferencedRecordSheet(
                citation: citation,
                loadDetail: { await viewModel.loadRecordDetail(for: $0) },
                onClose: { selectedCitation = nil }
            )
        }
        .fullScreenCover(item: $previewImageItem) { item in
            AgentSentImagePreviewSheet(attachment: item) {
                previewImageItem = nil
            }
        }
        .photosPicker(
            isPresented: $showPhotosPicker,
            selection: $selectedPhotoItems,
            maxSelectionCount: max(1, AgentImageValidator.maxCount - viewModel.attachedImages.count),
            matching: .images
        )
        .onChange(of: selectedPhotoItems) { _, items in
            guard !items.isEmpty else { return }
            let captured = items
            selectedPhotoItems = []
            let draftToken = viewModel.beginImageProcessing()
            Task {
                var loadedData: [Data] = []
                for item in captured {
                    if let raw = try? await item.loadTransferable(type: Data.self),
                       let normalized = AgentImageDataNormalizer.normalize(rawData: raw) {
                        loadedData.append(normalized)
                    }
                }
                viewModel.appendValidatedImageData(loadedData, draftToken: draftToken)
                viewModel.finishImageProcessing(token: draftToken)
            }
        }
        .fullScreenCover(isPresented: $showCameraSheet) {
            AgentCameraPicker(
                onImageCaptured: { data in
                    showCameraSheet = false
                    guard let data else { return }
                    let token = viewModel.beginImageProcessing()
                    viewModel.appendValidatedImageData([data], draftToken: token)
                    viewModel.finishImageProcessing(token: token)
                },
                onCancel: {
                    showCameraSheet = false
                }
            )
            .ignoresSafeArea()
        }
        .onChange(of: isInputFocused) { _, focused in
            if focused && viewModel.isPlusMenuOpen {
                viewModel.isPlusMenuOpen = false
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
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button {
                    isInputFocused = false
                    viewModel.dismissComposerOverlays()
                    onOpenSessionsDrawer?()
                } label: {
                    Image(systemName: "line.3.horizontal")
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .frame(width: 36, height: 40)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("agent.sessions.title")

                HStack(alignment: .center, spacing: 10) {
                    if let session = viewModel.activeSession, !viewModel.messages.isEmpty {
                        HStack(spacing: 5) {
                            Text(verbatim: session.title)
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                                .truncationMode(.tail)

                            if viewModel.activeSessionIsReadOnly {
                                Text("agent.feishu.readonly_badge")
                                    .font(.caption2.weight(.medium))
                                    .foregroundStyle(.secondary)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color(uiColor: .secondarySystemBackground), in: Capsule())
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        Spacer(minLength: 0)
                    }

                    topBarContextBadge
                }
                .frame(maxWidth: .infinity)

                HStack(spacing: 2) {
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
                            .frame(width: 36, height: 40)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("agent.new_chat")
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 12)
            .padding(.top, 2)
            .padding(.bottom, 6)

            Divider().opacity(0.28)
        }
    }

    private var topBarContextBadge: some View {
        let snapshot = viewModel.sessionTokenSnapshot
        let context = viewModel.contextWindowMetrics

        let consumedText: String = {
            if let snapshot, snapshot.totalTokens > 0 {
                return AgentTokenUsageAnalyzer.formatCompactTokenCount(snapshot.totalTokens)
            }
            if let snapshot, snapshot.estimatedInputTokens > 0 {
                return AgentTokenUsageAnalyzer.formatCompactTokenCount(snapshot.estimatedInputTokens)
            }
            return "0"
        }()

        let contextText: String = {
            if let current = context.currentContextTokens {
                let currentStr = AgentTokenUsageAnalyzer.formatCompactTokenCount(current)
                if let limit = context.contextWindowLimit, let pct = context.occupancyPercentText {
                    let limitStr = AgentTokenUsageAnalyzer.formatCompactTokenCount(limit)
                    return "\(currentStr)/\(limitStr) · \(pct)"
                }
                return currentStr
            }
            if let limit = context.contextWindowLimit {
                return "0/\(AgentTokenUsageAnalyzer.formatCompactTokenCount(limit))"
            }
            return String(localized: "agent.usage.context.ready")
        }()

        let ringColor: Color = {
            guard let ratio = context.occupancyRatio else { return AppColors.primaryBlue }
            if ratio >= 0.90 { return AppColors.overviewExpense }
            if ratio >= 0.70 { return Color.orange }
            return AppColors.primaryBlue
        }()

        return Button {
            isInputFocused = false
            viewModel.dismissComposerOverlays()
            tokenUsageInitialMessageId = nil
            showTokenUsageSheet = true
        } label: {
            VStack(alignment: .trailing, spacing: 2) {
                HStack(spacing: 4) {
                    ZStack {
                        Circle()
                            .stroke(Color(uiColor: .tertiarySystemFill), lineWidth: 1.6)
                        Circle()
                            .trim(from: 0, to: CGFloat(context.occupancyRatio ?? 0))
                            .stroke(ringColor, style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                    }
                    .frame(width: 9, height: 9)

                    Text(verbatim: contextText)
                        .font(.system(size: 11, weight: .medium).monospacedDigit())
                        .foregroundStyle(context.occupancyRatio != nil ? .primary : .secondary)
                        .lineLimit(1)
                }

                HStack(spacing: 3) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 8.5, weight: .semibold))
                        .foregroundStyle(snapshot?.hasReportedUsage == true ? AppColors.primaryBlue : .secondary)
                    Text(String(localized: "agent.usage.topbar.consumed \(consumedText)"))
                        .font(.system(size: 10, weight: .regular).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .layoutPriority(1)
        .accessibilityLabel("agent.usage.sheet_title")
    }

    private var sessionOptionsMenu: some View {
        Menu {
            Button {
                isInputFocused = false
                viewModel.dismissComposerOverlays()
                tokenUsageInitialMessageId = nil
                showTokenUsageSheet = true
            } label: {
                Label("agent.usage.menu_item", systemImage: "chart.pie")
            }

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
                .frame(width: 32, height: 40)
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
                                    let split = message.role == .assistant
                                        ? AgentTimelineBuilder.splitProcessAndFinalBlocks(for: message)
                                        : nil
                                    let citations = (message.role == .assistant && !message.isStreaming)
                                        ? viewModel.citedReferences(forAssistantIndex: idx, finalText: split?.finalText ?? "")
                                        : []
                                    AgentMessageRow(
                                        message: message,
                                        elapsedSeconds: message.isStreaming ? viewModel.elapsedSeconds : message.durationSeconds,
                                        citedReferences: citations,
                                        onOpenTurnUsage: {
                                            isInputFocused = false
                                            viewModel.dismissComposerOverlays()
                                            tokenUsageInitialMessageId = message.id
                                            showTokenUsageSheet = true
                                        },
                                        onSelectCitation: { citation in
                                            isInputFocused = false
                                            selectedCitation = citation
                                        },
                                        loadReplyImage: viewModel.loadReplyImage,
                                        onPreviewImage: { attachment in
                                            isInputFocused = false
                                            previewImageItem = attachment
                                        }
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

    // MARK: - 底部一体式极简胶囊输入区（含「+」分类入口、模型与权限控制）

    @ViewBuilder
    private var composerFloatingOverlay: some View {
        if viewModel.isPlusMenuOpen || viewModel.isRecordPickerOpen {
            ZStack(alignment: .bottom) {
                Color.black.opacity(0.001)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) {
                            viewModel.dismissComposerOverlays()
                        }
                    }

                Group {
                    if viewModel.isRecordPickerOpen {
                        AgentRecordReferencePanel(viewModel: viewModel) {
                            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) {
                                viewModel.isRecordPickerOpen = false
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                        .transition(
                            reduceMotion
                                ? .opacity
                                : .move(edge: .bottom).combined(with: .opacity)
                        )
                    } else if viewModel.isPlusMenuOpen {
                        HStack {
                            AgentPlusCategoryMenu(
                                imageCount: viewModel.attachedImages.count,
                                recordCount: viewModel.selectedRecordReferences.count,
                                onTakePhoto: {
                                    viewModel.isPlusMenuOpen = false
                                    isInputFocused = false
                                    if AgentCameraPicker.isCameraAvailable {
                                        showCameraSheet = true
                                    } else {
                                        viewModel.errorMessage = String(localized: "agent.image.error.camera_unavailable")
                                    }
                                },
                                onPickPhotos: {
                                    viewModel.isPlusMenuOpen = false
                                    isInputFocused = false
                                    selectedPhotoItems = []
                                    showPhotosPicker = true
                                },
                                onOpenRecords: {
                                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                                        viewModel.openRecordPicker()
                                    }
                                }
                            )
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                        .transition(
                            reduceMotion
                                ? .opacity
                                : .scale(scale: 0.94, anchor: .bottomLeading).combined(with: .opacity)
                        )
                    }
                }
            }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: viewModel.isPlusMenuOpen)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.2), value: viewModel.isRecordPickerOpen)
        }
    }

    private var composerBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !viewModel.attachedImages.isEmpty
                || !viewModel.selectedRecordReferences.isEmpty
                || viewModel.isProcessingImages {
                AgentComposerAttachmentsBar(
                    images: viewModel.attachedImages,
                    records: viewModel.selectedRecordReferences,
                    isProcessingImages: viewModel.isProcessingImages,
                    onRemoveImage: { viewModel.removeImage(id: $0) },
                    onRemoveRecord: { viewModel.removeRecordReference(id: $0) }
                )
            }

            HStack(alignment: .bottom, spacing: 6) {
                Button {
                    isInputFocused = false
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) {
                        viewModel.togglePlusMenu()
                    }
                } label: {
                    let isOverlayActive = viewModel.isPlusMenuOpen || viewModel.isRecordPickerOpen
                    Image(systemName: isOverlayActive ? "xmark" : "plus")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(isOverlayActive ? AppColors.primaryBlue : .primary)
                        .frame(width: 30, height: 30)
                        .background(
                            isOverlayActive
                                ? AppColors.primaryBlue.opacity(0.14)
                                : Color(uiColor: .tertiarySystemFill).opacity(0.75),
                            in: Circle()
                        )
                        .frame(width: 38, height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(viewModel.activeSessionIsReadOnly)
                .accessibilityLabel("agent.plus_menu.accessibility_label")

                TextField("agent.input.placeholder", text: $viewModel.draftText, axis: .vertical)
                    .font(.body)
                    .lineLimit(1...6)
                    .focused($isInputFocused)
                    .padding(.vertical, 6)
                    .disabled(viewModel.activeSessionIsReadOnly)
            }
            .padding(.leading, 8)
            .padding(.trailing, 14)
            .padding(.top, viewModel.attachedImages.isEmpty && viewModel.selectedRecordReferences.isEmpty ? 6 : 2)
            .padding(.bottom, 2)

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
    let citedReferences: [AgentCitedReference]
    let onOpenTurnUsage: () -> Void
    let onSelectCitation: (AgentCitedReference) -> Void
    let loadReplyImage: (String) async throws -> Data
    let onPreviewImage: (AgentChatImageAttachment) -> Void
    @State private var copied = false

    var body: some View {
        if message.role == .user {
            userBubble
        } else {
            assistantBody
        }
    }

    private var userBubble: some View {
        let parsed = AgentReferenceCitationParser.parseUserPromptAndReferences(message.content)
        let imagePlaceholder = String(localized: "agent.image.sent_placeholder")
        let shouldShowTextBubble = !parsed.referenceTitles.isEmpty
            || (!parsed.prompt.isEmpty && (message.images.isEmpty || parsed.prompt != imagePlaceholder))

        return HStack {
            Spacer(minLength: 48)
            VStack(alignment: .trailing, spacing: 8) {
                if !message.images.isEmpty {
                    userImagesView
                }

                if shouldShowTextBubble {
                    VStack(alignment: .leading, spacing: 6) {
                        if !parsed.referenceTitles.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach(parsed.referenceTitles, id: \.self) { title in
                                        HStack(spacing: 4) {
                                            Image(systemName: "book.pages")
                                                .font(.system(size: 10, weight: .semibold))
                                                .foregroundStyle(AppColors.primaryBlue)
                                            Text(verbatim: title)
                                                .font(.caption2.weight(.medium))
                                                .foregroundStyle(.primary)
                                                .lineLimit(1)
                                        }
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(
                                            AppColors.primaryBlue.opacity(0.10),
                                            in: Capsule()
                                        )
                                    }
                                }
                            }
                        }

                        if !parsed.prompt.isEmpty {
                            Text(verbatim: parsed.prompt)
                                .font(.callout)
                                .lineSpacing(4.5)
                                .foregroundStyle(.primary)
                                .textSelection(.enabled)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(
                        Color(uiColor: .secondarySystemBackground),
                        in: RoundedRectangle(cornerRadius: 20, style: .continuous)
                    )
                }
            }
        }
    }

    @ViewBuilder
    private var userImagesView: some View {
        if message.images.count == 1, let single = message.images.first, let uiImage = UIImage(data: single.data) {
            Button {
                onPreviewImage(single)
            } label: {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: 210, maxHeight: 210)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .strokeBorder(Color(uiColor: .separator).opacity(0.22), lineWidth: 0.5)
                    }
            }
            .buttonStyle(.plain)
        } else {
            LazyVGrid(
                columns: [GridItem(.fixed(98), spacing: 6), GridItem(.fixed(98), spacing: 6)],
                alignment: .trailing,
                spacing: 6
            ) {
                ForEach(message.images) { item in
                    if let uiImage = UIImage(data: item.data) {
                        Button {
                            onPreviewImage(item)
                        } label: {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 98, height: 98)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                .overlay {
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .strokeBorder(Color(uiColor: .separator).opacity(0.22), lineWidth: 0.5)
                                }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private var assistantBody: some View {
        let split = AgentTimelineBuilder.splitProcessAndFinalBlocks(for: message)
        let trimmedFinal = split.finalText.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasTurnTokenInfo = message.usage.map {
            $0.totalTokens > 0 || ($0.reportedCallCount ?? 0) > 0 || ($0.estimatedInputTokens ?? 0) > 0
        } ?? false
        let showFooter = !message.isStreaming && (!trimmedFinal.isEmpty || hasTurnTokenInfo || message.status != nil)

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

            if !message.replyImages.isEmpty {
                AgentReplyImageGallery(attachments: message.replyImages, load: loadReplyImage)
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

            if !message.isStreaming && !citedReferences.isEmpty {
                AgentCitedReferencesStrip(
                    references: citedReferences,
                    onSelect: onSelectCitation
                )
            }

            if showFooter {
                HStack(spacing: 10) {
                    if hasTurnTokenInfo, let usage = message.usage {
                        let tokenCount = usage.totalTokens > 0
                            ? usage.totalTokens
                            : (usage.estimatedInputTokens ?? 0)
                        Button(action: onOpenTurnUsage) {
                            HStack(spacing: 4) {
                                Image(systemName: "chart.bar")
                                    .font(.system(size: 10.5, weight: .semibold))
                                    .foregroundStyle(AppColors.primaryBlue)
                                Text(verbatim: "\(AgentTokenUsageAnalyzer.formatTokenCount(tokenCount)) tokens")
                                    .font(.caption2.weight(.medium).monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color(uiColor: .secondarySystemBackground), in: Capsule())
                            .contentShape(Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("agent.usage.scope.turn_detail")
                    }

                    Text(verbatim: AgentTokenUsageAnalyzer.formatTimestampSeconds(message.createdAt))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)

                    if !trimmedFinal.isEmpty {
                        Button {
                            UIPasteboard.general.string = AgentInlineMarkdownParser.stripMarkupTagsOnly(trimmedFinal)
                            copied = true
                            Task {
                                try? await Task.sleep(for: .seconds(1.5))
                                copied = false
                            }
                        } label: {
                            HStack(spacing: 3) {
                                Image(systemName: copied ? "checkmark" : "doc.on.doc")
                                    .font(.caption.weight(.medium))
                                if copied {
                                    Text("agent.copied")
                                        .font(.caption2.weight(.medium))
                                }
                            }
                            .foregroundStyle(copied ? AppColors.primaryBlue : .secondary)
                            .frame(minWidth: 26, minHeight: 26)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .sensoryFeedback(.impact(flexibility: .soft, intensity: 0.6), trigger: copied)
                        .accessibilityLabel(copied ? LocalizedStringKey("agent.copied") : LocalizedStringKey("agent.copy_response"))
                    }

                    Spacer(minLength: 0)
                }
                .padding(.top, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
