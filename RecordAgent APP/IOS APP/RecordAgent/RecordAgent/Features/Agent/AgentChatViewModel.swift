import Foundation
import SwiftUI

/// 管理 Agent 对话流、会话切换、权限审批与模型配置的视图模型。
@MainActor
final class AgentChatViewModel: ObservableObject {
    @Published private(set) var sessions: [AgentSessionSummary] = []
    @Published private(set) var feishuSessions: [AgentSessionSummary] = []
    @Published private(set) var activeSessionId: String?
    @Published private(set) var activeSessionIsReadOnly = false
    @Published private(set) var messages: [AgentChatMessage] = []
    @Published private(set) var permissionMode: AgentSessionPermissionMode = .ask
    @Published private(set) var pendingApproval: AgentPendingApproval?
    @Published var approvalSelections: [String: AgentPermissionDecisionInput] = [:]

    @Published private(set) var isStreaming = false
    @Published private(set) var isStopping = false
    @Published private(set) var isSubmittingApproval = false
    @Published private(set) var streamingRunId: String?
    @Published private(set) var elapsedSeconds = 0
    @Published var draftText = ""

    @Published private(set) var sessionUsageSummary: AgentTokenUsageSummary?
    @Published private(set) var attachedImages: [AgentChatImageAttachment] = []
    @Published private(set) var isProcessingImages = false
    @Published private(set) var selectedRecordReferences: [AgentRecordReferenceOption] = []
    @Published private(set) var recordMetadataByNormalizedTitle: [String: AgentRecordReferenceOption] = [:]

    @Published var isPlusMenuOpen = false
    @Published var isRecordPickerOpen = false
    @Published var recordSearchQuery = ""
    @Published private(set) var recordReferenceOptions: [AgentRecordReferenceOption] = []
    @Published private(set) var isLoadingRecordReferences = false
    @Published private(set) var isLoadingMoreRecordReferences = false
    @Published private(set) var recordReferenceError: String?
    @Published private(set) var recordReferencesHasMore = false

    @Published private(set) var modelConfig: AgentModelConfig?
    @Published private(set) var fullModelConfig: AgentFullModelConfig?
    @Published private(set) var isSwitchingModel = false
    @Published private(set) var activePlanContent: String?
    @Published private(set) var routingNotice: AgentRoutingNotice?
    @Published private(set) var subagentNotices: [AgentSubagentProgress] = []

    @Published private(set) var isLoadingInitial = false
    @Published private(set) var isLoadingSessions = false
    @Published private(set) var isLoadingMessages = false
    @Published var errorMessage: String?

    private let service: any AgentServiceProtocol
    private var hasLoadedOnce = false
    private var loadGeneration = 0
    private var draftGeneration = 0
    private var recordSearchGeneration = 0
    private var recordReferencesNextOffset = 0
    private var recordSearchTask: Task<Void, Never>?
    private var streamTask: Task<Void, Never>?
    private var timerTask: Task<Void, Never>?
    /// 仅当前客户端进程内存中的图片展示缓存；后端不持久化原图，重启后历史只剩文字。
    private var sessionInMemoryImages: [String: [InMemoryTurnImages]] = [:]

    private struct InMemoryTurnImages {
        let localDisplayContent: String
        let isImageOnly: Bool
        let images: [AgentChatImageAttachment]
    }

    static let maxRecordReferences = 4

    init(service: any AgentServiceProtocol) {
        self.service = service
    }

    var activeSession: AgentSessionSummary? {
        guard let activeSessionId else { return nil }
        if activeSessionIsReadOnly {
            return feishuSessions.first { $0.id == activeSessionId }
        }
        return sessions.first { $0.id == activeSessionId }
    }

    var currentModelDisplayName: String {
        if let name = modelConfig?.displayName, !name.isEmpty {
            return name
        }
        if let active = fullModelConfig?.activeModel, !active.isEmpty {
            return active
        }
        return String(localized: "agent.model.unset")
    }

    var enabledProviders: [AgentProviderConfig] {
        (fullModelConfig?.providers ?? []).filter { provider in
            (provider.isEnabled ?? true) && !(provider.models?.isEmpty ?? true)
        }
    }

    struct SelectableModelOption: Identifiable {
        let provider: AgentProviderConfig
        let item: AgentModelItem
        var id: String { "\(provider.id)::\(item.id)" }
    }

    var flattenedModels: [SelectableModelOption] {
        enabledProviders.flatMap { provider in
            (provider.models ?? []).map { SelectableModelOption(provider: provider, item: $0) }
        }
    }

    var currentSessionHasPlan: Bool {
        if let approval = pendingApproval,
           approval.tools.contains(where: { $0.toolName == "plan_exit" }) {
            return true
        }
        return messages.contains { msg in
            msg.tools.contains { $0.toolName == "plan_exit" || $0.toolName == "plan_write" }
                || msg.blocks.contains { block in
                    block.tools.contains { $0.toolName == "plan_exit" || $0.toolName == "plan_write" }
                }
        }
    }

    func isCurrentModel(provider: AgentProviderConfig, item: AgentModelItem) -> Bool {
        if let name = modelConfig?.name, let vendor = modelConfig?.vendor,
           name == item.modelName && vendor == provider.type {
            return true
        }
        if let active = fullModelConfig?.activeModel, let activeVendor = fullModelConfig?.activeVendor,
           active == item.modelName && activeVendor == provider.type {
            return true
        }
        return false
    }

    var currentModelSupportsVision: Bool {
        if let explicit = modelConfig?.supportsVision {
            return explicit
        }
        if let activeVendor = fullModelConfig?.activeVendor ?? modelConfig?.vendor,
           let activeModel = fullModelConfig?.activeModel ?? modelConfig?.name {
            for provider in fullModelConfig?.providers ?? [] where provider.type == activeVendor {
                if let match = (provider.models ?? []).first(where: { $0.modelName == activeModel }) {
                    return match.supportsVision == true
                }
            }
        }
        return false
    }

    var turnUsageOptions: [AgentTokenUsageTurnOption] {
        AgentTokenUsageAnalyzer.turnOptions(from: messages)
    }

    var sessionTokenSnapshot: AgentTokenUsageSnapshot? {
        AgentTokenUsageAnalyzer.aggregateSessionUsage(
            turns: turnUsageOptions,
            summary: sessionUsageSummary
        )
    }

    func tokenSnapshot(for selectedMessageId: String?) -> AgentTokenUsageSnapshot? {
        if let selectedMessageId,
           let match = turnUsageOptions.first(where: { $0.messageId == selectedMessageId }) {
            return AgentTokenUsageAnalyzer.snapshot(for: match.usage)
        }
        return sessionTokenSnapshot
    }

    var currentModelConfiguredContextWindow: Int64? {
        if let explicit = modelConfig?.contextWindow, explicit > 0 {
            return explicit
        }
        if let activeVendor = fullModelConfig?.activeVendor ?? modelConfig?.vendor,
           let activeModel = fullModelConfig?.activeModel ?? modelConfig?.name {
            for provider in fullModelConfig?.providers ?? [] where provider.type == activeVendor {
                if let match = (provider.models ?? []).first(where: { $0.modelName == activeModel }),
                   let window = match.contextWindow, window > 0 {
                    return window
                }
            }
        }
        return nil
    }

    var contextWindowMetrics: AgentContextWindowMetrics {
        AgentTokenUsageAnalyzer.latestContextMetrics(
            from: messages,
            modelName: modelConfig?.name ?? fullModelConfig?.activeModel,
            vendor: modelConfig?.vendor ?? fullModelConfig?.activeVendor,
            configuredLimit: currentModelConfiguredContextWindow
        )
    }

    func citedReferences(forAssistantIndex index: Int, finalText: String) -> [AgentCitedReference] {
        AgentReferenceCitationParser.citedReferences(
            forAssistantIndex: index,
            in: messages,
            finalText: finalText,
            cachedMetadata: recordMetadataByNormalizedTitle
        )
    }

    func loadRecordDetail(for citation: AgentCitedReference) async -> Result<AgentRecordDetail, Error> {
        do {
            if let recordId = citation.recordId, !recordId.isEmpty {
                let detail = try await service.fetchRecordDetail(id: recordId)
                return .success(detail)
            }
            let page = try await service.fetchRecordReferences(query: citation.title, limit: 10, offset: 0)
            cacheRecordMetadata(page.items)
            let exact = page.items.first {
                $0.displayTitle.caseInsensitiveCompare(citation.title) == .orderedSame
            } ?? page.items.first
            guard let matched = exact else {
                throw APIClientError.serverMessage(String(localized: "agent.reference.detail_not_found"))
            }
            let detail = try await service.fetchRecordDetail(id: matched.id)
            return .success(detail)
        } catch {
            return .failure(error)
        }
    }

    var canSend: Bool {
        guard !activeSessionIsReadOnly, !isStreaming, pendingApproval == nil, !isProcessingImages else {
            return false
        }
        let hasText = !draftText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        if !selectedRecordReferences.isEmpty {
            return hasText
        }
        return hasText || !attachedImages.isEmpty
    }

    func loadInitialIfNeeded() async {
        guard !hasLoadedOnce else { return }
        hasLoadedOnce = true
        isLoadingInitial = true
        defer { isLoadingInitial = false }

        async let loadedSessions = loadSessionsInternal()
        async let loadedModels = refreshModelState()
        _ = await (loadedSessions, loadedModels)

        if activeSessionId == nil, let latest = sessions.first {
            await selectSession(latest, readOnly: false)
        }
    }

    func refreshAll() async {
        await loadSessionsInternal()
        await refreshModelState()
        if let activeSessionId {
            await reloadSessionDetail(sessionId: activeSessionId, readOnly: activeSessionIsReadOnly)
        }
    }

    func refreshSessions() async {
        await loadSessionsInternal()
    }

    /// 点击主动推送通知直达对应会话并加载最新消息。
    func openSessionFromNotification(sessionId: String?) async {
        if !hasLoadedOnce {
            await loadInitialIfNeeded()
        } else {
            await loadSessionsInternal()
        }
        guard let sessionId, !sessionId.isEmpty else { return }
        guard !isStreaming else { return }
        if activeSessionId == sessionId {
            await reloadSessionDetail(sessionId: sessionId, readOnly: false)
            return
        }
        if let target = sessions.first(where: { $0.id == sessionId }) {
            await selectSession(target, readOnly: false)
        } else {
            activeSessionId = sessionId
            activeSessionIsReadOnly = false
            await reloadSessionDetail(sessionId: sessionId, readOnly: false)
        }
    }

    /// 收到新的主动陪伴或信号提醒时，刷新当前未处于流式输出中的主对话。
    func refreshActiveSessionIfCompanionUpdated() async {
        guard !isStreaming, !activeSessionIsReadOnly else { return }
        await loadSessionsInternal()
        if let activeSessionId {
            await reloadSessionDetail(sessionId: activeSessionId, readOnly: false)
        }
    }

    func refreshModelState() async {
        do {
            async let config = service.fetchModelConfig()
            async let full = service.fetchFullModelConfig()
            let (resolvedConfig, resolvedFull) = try await (config, full)
            modelConfig = resolvedConfig
            fullModelConfig = resolvedFull
        } catch {
            // 仅静默降级展示，不阻断对话主流程。
            if let fallback = try? await service.fetchModelConfig() {
                modelConfig = fallback
            }
        }
    }

    func startNewChat() {
        cancelActiveStreamLocally()
        loadGeneration += 1
        clearDraftAttachmentsAndPanels()
        activeSessionId = nil
        activeSessionIsReadOnly = false
        messages = []
        sessionUsageSummary = nil
        pendingApproval = nil
        approvalSelections = [:]
        activePlanContent = nil
        routingNotice = nil
        subagentNotices = []
        errorMessage = nil
        permissionMode = .ask
    }

    func selectSession(_ summary: AgentSessionSummary, readOnly: Bool) async {
        cancelActiveStreamLocally()
        clearDraftAttachmentsAndPanels()
        activeSessionId = summary.id
        activeSessionIsReadOnly = readOnly
        sessionUsageSummary = nil
        pendingApproval = nil
        approvalSelections = [:]
        activePlanContent = nil
        routingNotice = nil
        subagentNotices = []
        errorMessage = nil
        await reloadSessionDetail(sessionId: summary.id, readOnly: readOnly)
    }

    func togglePlusMenu() {
        if isRecordPickerOpen {
            isRecordPickerOpen = false
        }
        isPlusMenuOpen.toggle()
    }

    func dismissComposerOverlays() {
        isPlusMenuOpen = false
        isRecordPickerOpen = false
    }

    func openRecordPicker() {
        isPlusMenuOpen = false
        isRecordPickerOpen = true
        if recordReferenceOptions.isEmpty {
            scheduleRecordSearch(recordSearchQuery, immediate: true)
        }
    }

    func scheduleRecordSearch(_ query: String, immediate: Bool = false) {
        recordSearchQuery = query
        recordSearchTask?.cancel()
        recordSearchGeneration += 1
        let expectedSearchGen = recordSearchGeneration
        let expectedDraftGen = draftGeneration

        recordSearchTask = Task { [weak self] in
            if !immediate {
                try? await Task.sleep(for: .milliseconds(180))
            }
            guard !Task.isCancelled, let self else { return }
            await self.fetchRecordPage(
                query: query,
                offset: 0,
                append: false,
                expectedSearchGen: expectedSearchGen,
                expectedDraftGen: expectedDraftGen
            )
        }
    }

    func loadMoreRecordReferences() async {
        guard isRecordPickerOpen, recordReferencesHasMore, !isLoadingMoreRecordReferences else { return }
        let expectedSearchGen = recordSearchGeneration
        let expectedDraftGen = draftGeneration
        await fetchRecordPage(
            query: recordSearchQuery,
            offset: recordReferencesNextOffset,
            append: true,
            expectedSearchGen: expectedSearchGen,
            expectedDraftGen: expectedDraftGen
        )
    }

    func toggleRecordReference(_ option: AgentRecordReferenceOption) {
        if let existingIdx = selectedRecordReferences.firstIndex(where: { $0.id == option.id }) {
            selectedRecordReferences.remove(at: existingIdx)
            return
        }
        guard selectedRecordReferences.count < Self.maxRecordReferences else {
            errorMessage = String(localized: "agent.reference.error.max_count")
            return
        }
        selectedRecordReferences.append(option)
        cacheRecordMetadata([option])
        isRecordPickerOpen = false
    }

    func removeRecordReference(id: String) {
        selectedRecordReferences.removeAll { $0.id == id }
    }

    func beginImageProcessing() -> Int {
        isProcessingImages = true
        return draftGeneration
    }

    func finishImageProcessing(token: Int) {
        guard token == draftGeneration else { return }
        isProcessingImages = false
    }

    func appendValidatedImageData(_ dataList: [Data], draftToken: Int) {
        guard draftToken == draftGeneration else { return }
        errorMessage = nil
        for data in dataList {
            guard draftToken == draftGeneration else { return }
            do {
                let attachment = try AgentImageValidator.makeAttachment(
                    from: data,
                    existing: attachedImages
                )
                attachedImages.append(attachment)
            } catch {
                errorMessage = error.localizedDescription
                break
            }
        }
    }

    func removeImage(id: UUID) {
        attachedImages.removeAll { $0.id == id }
    }

    func sendPrompt(_ text: String) async {
        draftText = text
        await sendCurrentDraft()
    }

    func sendCurrentDraft() async {
        let trimmed = draftText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !activeSessionIsReadOnly, !isStreaming, pendingApproval == nil, !isProcessingImages else {
            return
        }
        if !attachedImages.isEmpty && !currentModelSupportsVision {
            errorMessage = String(localized: "agent.image.error.vision_required")
            return
        }
        if !selectedRecordReferences.isEmpty && trimmed.isEmpty {
            errorMessage = String(localized: "agent.reference.error.question_required")
            return
        }
        guard !trimmed.isEmpty || !attachedImages.isEmpty else {
            return
        }

        errorMessage = nil
        routingNotice = nil
        subagentNotices = []
        isPlusMenuOpen = false
        isRecordPickerOpen = false

        let outgoingText = trimmed
        let outgoingImages = attachedImages
        let outgoingRecords = selectedRecordReferences

        let serverContent: String
        let localDisplayContent: String
        if !outgoingRecords.isEmpty {
            let titles = outgoingRecords.map(\.displayTitle).joined(separator: "、")
            let formatted = "[资料：\(titles)] \(outgoingText)"
            serverContent = formatted
            localDisplayContent = formatted
        } else if outgoingText.isEmpty {
            serverContent = ""
            localDisplayContent = String(localized: "agent.image.sent_placeholder")
        } else {
            serverContent = outgoingText
            localDisplayContent = outgoingText
        }

        draftText = ""
        attachedImages = []
        selectedRecordReferences = []

        let targetSessionId: String
        let wasEmptySession: Bool
        if let existingId = activeSessionId {
            targetSessionId = existingId
            wasEmptySession = messages.isEmpty
        } else {
            do {
                let defaultTitle = String(localized: "agent.session.default_title")
                let created = try await service.createSession(kind: .general, title: defaultTitle, projectId: nil)
                sessions.insert(created, at: 0)
                activeSessionId = created.id
                activeSessionIsReadOnly = false
                targetSessionId = created.id
                wasEmptySession = true
            } catch {
                draftText = outgoingText
                attachedImages = outgoingImages
                selectedRecordReferences = outgoingRecords
                errorMessage = Self.userFacingError(error)
                return
            }
        }

        if !outgoingImages.isEmpty {
            sessionInMemoryImages[targetSessionId, default: []].append(
                InMemoryTurnImages(
                    localDisplayContent: localDisplayContent,
                    isImageOnly: outgoingText.isEmpty,
                    images: outgoingImages
                )
            )
        }

        let userMessage = AgentChatMessage(
            id: "local_user_\(UUID().uuidString)",
            turnId: nil,
            role: .user,
            content: localDisplayContent,
            thinking: nil,
            createdAt: Date(),
            status: .completed,
            durationSeconds: nil,
            tools: [],
            blocks: [],
            proactive: false,
            usage: nil,
            isStreaming: false,
            images: outgoingImages
        )
        let assistantId = "local_assistant_\(UUID().uuidString)"
        let assistantMessage = AgentChatMessage(
            id: assistantId,
            turnId: nil,
            role: .assistant,
            content: "",
            thinking: nil,
            createdAt: Date(),
            status: nil,
            durationSeconds: nil,
            tools: [],
            blocks: [],
            proactive: false,
            usage: nil,
            isStreaming: true
        )
        messages.append(userMessage)
        messages.append(assistantMessage)

        let runId = UUID().uuidString.lowercased()
        let stream = service.streamChat(
            runId: runId,
            sessionId: targetSessionId,
            content: serverContent,
            context: outgoingText,
            recordReferenceIds: outgoingRecords.map(\.id),
            images: outgoingImages.map(\.payload)
        )
        startStreamConsumption(
            stream,
            sessionId: targetSessionId,
            assistantMessageId: assistantId,
            initialRunId: runId,
            shouldGenerateTitle: wasEmptySession
        )
    }

    func stopStreaming() async {
        guard isStreaming, let sessionId = activeSessionId, let runId = streamingRunId else { return }
        isStopping = true
        do {
            _ = try await service.cancelRun(sessionId: sessionId, runId: runId)
        } catch {
            // 若后端停止请求失败，中止本地消费并回读权威会话状态。
            cancelActiveStreamLocally()
            errorMessage = Self.userFacingError(error)
            await reloadSessionDetail(sessionId: sessionId, readOnly: false)
        }
    }

    func setApproval(toolCallId: String, approved: Bool, rememberForSession: Bool? = nil) {
        let existing = approvalSelections[toolCallId]
        approvalSelections[toolCallId] = AgentPermissionDecisionInput(
            toolCallId: toolCallId,
            approved: approved,
            rememberForSession: rememberForSession ?? existing?.rememberForSession ?? false
        )
    }

    func submitApprovalDecisions(overrideAllApproved: Bool? = nil) async {
        guard let approval = pendingApproval,
              let sessionId = activeSessionId,
              !isSubmittingApproval else {
            return
        }

        let decisions: [AgentPermissionDecisionInput] = approval.tools.map { tool in
            let current = approvalSelections[tool.toolCallId]
            return AgentPermissionDecisionInput(
                toolCallId: tool.toolCallId,
                approved: overrideAllApproved ?? current?.approved ?? true,
                rememberForSession: current?.rememberForSession ?? false
            )
        }

        isSubmittingApproval = true
        errorMessage = nil
        defer { isSubmittingApproval = false }

        do {
            let response = try await service.submitPermissionDecisions(
                sessionId: sessionId,
                approvalId: approval.approvalId,
                decisions: decisions
            )
            pendingApproval = nil
            approvalSelections = [:]

            if response.readyToResume {
                let targetId = ensureStreamingAssistantMessage(
                    partialContent: approval.partialContent
                )
                let stream = service.resumeChat(
                    sessionId: sessionId,
                    approvalId: approval.approvalId,
                    runId: approval.runId
                )
                startStreamConsumption(
                    stream,
                    sessionId: sessionId,
                    assistantMessageId: targetId,
                    initialRunId: approval.runId,
                    shouldGenerateTitle: false
                )
            }
        } catch {
            errorMessage = Self.userFacingError(error)
        }
    }

    func renameSession(_ summary: AgentSessionSummary, newTitle: String) async {
        let trimmed = newTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        do {
            let updated = try await service.updateSessionTitle(sessionId: summary.id, title: trimmed)
            if let idx = sessions.firstIndex(where: { $0.id == updated.id }) {
                sessions[idx] = updated
            }
        } catch {
            errorMessage = Self.userFacingError(error)
        }
    }

    func deleteSession(_ summary: AgentSessionSummary) async {
        do {
            try await service.deleteSession(sessionId: summary.id)
            sessions.removeAll { $0.id == summary.id }
            if activeSessionId == summary.id {
                startNewChat()
                if let next = sessions.first {
                    await selectSession(next, readOnly: false)
                }
            }
        } catch {
            errorMessage = Self.userFacingError(error)
        }
    }

    func updatePermissionMode(_ mode: AgentSessionPermissionMode) async {
        guard let sessionId = activeSessionId, !activeSessionIsReadOnly else {
            permissionMode = mode
            return
        }
        let previous = permissionMode
        permissionMode = mode
        do {
            permissionMode = try await service.updatePermissionMode(sessionId: sessionId, mode: mode)
        } catch {
            permissionMode = previous
            errorMessage = Self.userFacingError(error)
        }
    }

    func loadPlan() async {
        guard let sessionId = activeSessionId else { return }
        do {
            activePlanContent = try await service.fetchPlan(sessionId: sessionId)
        } catch {
            errorMessage = Self.userFacingError(error)
        }
    }

    func switchModel(provider: AgentProviderConfig, item: AgentModelItem) async -> Bool {
        guard !isSwitchingModel else { return false }
        isSwitchingModel = true
        errorMessage = nil
        defer { isSwitchingModel = false }

        do {
            try await service.switchModel(
                vendor: provider.type,
                modelName: item.modelName,
                apiKey: provider.apiKey ?? "",
                baseUrl: provider.baseUrl?.isEmpty == false ? provider.baseUrl : nil,
                protocolName: provider.protocol?.isEmpty == false ? provider.protocol : nil
            )
            await refreshModelState()
            return true
        } catch {
            errorMessage = Self.userFacingError(error)
            return false
        }
    }

    // MARK: - 内部流式消费与状态同步

    private func loadSessionsInternal() async {
        isLoadingSessions = true
        defer { isLoadingSessions = false }
        do {
            async let mainList = service.listSessions()
            async let feishuList = service.listFeishuSessions()
            let (resolvedMain, resolvedFeishu) = try await (mainList, feishuList)
            sessions = resolvedMain.sorted { $0.updatedAt > $1.updatedAt }
            feishuSessions = resolvedFeishu.sorted { $0.updatedAt > $1.updatedAt }
        } catch {
            errorMessage = Self.userFacingError(error)
        }
    }

    private func reloadSessionDetail(sessionId: String, readOnly: Bool) async {
        loadGeneration += 1
        let expectedGen = loadGeneration
        isLoadingMessages = true
        defer {
            if loadGeneration == expectedGen {
                isLoadingMessages = false
            }
        }

        do {
            let detail: AgentSessionDetailDTO
            if readOnly {
                detail = try await service.getFeishuSessionDetail(sessionId: sessionId)
            } else {
                detail = try await service.getSessionDetail(sessionId: sessionId)
            }
            guard loadGeneration == expectedGen, activeSessionId == sessionId else { return }

            let mapped = AgentTimelineBuilder.mapTranscriptMessages(detail.messages)
            messages = attachingInMemoryImages(to: mapped, sessionId: sessionId)
            sessionUsageSummary = detail.usageSummary
            if !readOnly {
                if let idx = sessions.firstIndex(where: { $0.id == detail.summary.id }) {
                    sessions[idx] = detail.summary
                }
                async let mode = try? service.getPermissionMode(sessionId: sessionId)
                async let pending = try? service.fetchPendingPermission(sessionId: sessionId)
                let (resolvedMode, resolvedPending) = await (mode, pending)
                guard loadGeneration == expectedGen, activeSessionId == sessionId else { return }
                if let resolvedMode { permissionMode = resolvedMode }
                if let resolvedPending {
                    setPendingApproval(resolvedPending)
                }
                if currentSessionHasPlan {
                    await loadPlan()
                } else {
                    activePlanContent = nil
                }
            }
        } catch {
            guard loadGeneration == expectedGen else { return }
            errorMessage = Self.userFacingError(error)
        }
    }

    private func startStreamConsumption(
        _ stream: AsyncThrowingStream<AgentStreamEvent, Error>,
        sessionId: String,
        assistantMessageId: String,
        initialRunId: String,
        shouldGenerateTitle: Bool
    ) {
        cancelActiveStreamLocally()
        isStreaming = true
        isStopping = false
        streamingRunId = initialRunId
        elapsedSeconds = 0

        timerTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                self.elapsedSeconds += 1
            }
        }

        streamTask = Task { [weak self] in
            guard let self else { return }
            var terminatedExplicitly = false
            do {
                for try await event in stream {
                    guard !Task.isCancelled else { return }
                    switch event {
                    case .runStarted(let serverRunId):
                        if !serverRunId.isEmpty {
                            self.streamingRunId = serverRunId
                        }

                    case .thinking(let chunk):
                        self.mutateMessage(id: assistantMessageId) {
                            AgentTimelineBuilder.appendThinking($0, chunk: chunk)
                        }

                    case .text(let chunk):
                        self.mutateMessage(id: assistantMessageId) {
                            AgentTimelineBuilder.appendText($0, chunk: chunk)
                        }

                    case .toolCall(let toolCallId, let toolName, let command):
                        self.mutateMessage(id: assistantMessageId) {
                            AgentTimelineBuilder.upsertToolCall(
                                $0,
                                toolCallId: toolCallId,
                                toolName: toolName,
                                command: command
                            )
                        }

                    case .toolResult(let toolCallId, _, let result, let status):
                        self.mutateMessage(id: assistantMessageId) {
                            AgentTimelineBuilder.applyToolResult(
                                $0,
                                toolCallId: toolCallId,
                                result: result,
                                status: status
                            )
                        }

                    case .routingNotice(let notice):
                        self.routingNotice = notice

                    case .subagent(let progress):
                        self.upsertSubagentNotice(progress)

                    case .permissionRequired(let approval):
                        terminatedExplicitly = true
                        self.finishStreamingFlags(
                            assistantMessageId: assistantMessageId,
                            status: nil
                        )
                        self.setPendingApproval(approval)

                    case .done:
                        terminatedExplicitly = true
                        self.finishStreamingFlags(
                            assistantMessageId: assistantMessageId,
                            status: .completed
                        )
                        await self.syncSessionAfterStream(
                            sessionId: sessionId,
                            shouldGenerateTitle: shouldGenerateTitle
                        )

                    case .cancelled:
                        terminatedExplicitly = true
                        self.finishStreamingFlags(
                            assistantMessageId: assistantMessageId,
                            status: .cancelled
                        )
                        await self.syncSessionAfterStream(
                            sessionId: sessionId,
                            shouldGenerateTitle: false
                        )

                    case .error(let message):
                        terminatedExplicitly = true
                        self.finishStreamingFlags(
                            assistantMessageId: assistantMessageId,
                            status: .failed
                        )
                        self.errorMessage = message
                    }
                }
                if !terminatedExplicitly {
                    self.finishStreamingFlags(
                        assistantMessageId: assistantMessageId,
                        status: .completed
                    )
                }
            } catch is CancellationError {
                self.finishStreamingFlags(assistantMessageId: assistantMessageId, status: .cancelled)
            } catch {
                self.finishStreamingFlags(assistantMessageId: assistantMessageId, status: .failed)
                self.errorMessage = Self.userFacingError(error)
            }
        }
    }

    func loadReplyImage(fileId: String) async throws -> Data {
        try await service.fetchReplyImage(fileId: fileId)
    }

    private func syncSessionAfterStream(sessionId: String, shouldGenerateTitle: Bool) async {
        if let detail = try? await service.getSessionDetail(sessionId: sessionId) {
            if let idx = sessions.firstIndex(where: { $0.id == detail.summary.id }) {
                sessions[idx] = detail.summary
            }
            if activeSessionId == sessionId {
                let mapped = AgentTimelineBuilder.mapTranscriptMessages(detail.messages)
                messages = attachingInMemoryImages(to: mapped, sessionId: sessionId)
                sessionUsageSummary = detail.usageSummary
            }
        }
        if shouldGenerateTitle, let generated = try? await service.generateSessionTitle(sessionId: sessionId) {
            if let idx = sessions.firstIndex(where: { $0.id == generated.id }) {
                sessions[idx] = generated
            }
        }
        if activeSessionId == sessionId {
            if currentSessionHasPlan {
                await loadPlan()
            } else {
                activePlanContent = nil
            }
        }
        sessions.sort { $0.updatedAt > $1.updatedAt }
    }

    private func finishStreamingFlags(
        assistantMessageId: String,
        status: AgentMessageStatus?
    ) {
        timerTask?.cancel()
        timerTask = nil
        let elapsed = elapsedSeconds
        isStreaming = false
        isStopping = false
        streamingRunId = nil
        mutateMessage(id: assistantMessageId) { msg in
            var copy = msg
            copy.isStreaming = false
            if let status {
                copy.status = status
            }
            if copy.durationSeconds == nil, elapsed > 0 {
                copy.durationSeconds = elapsed
            }
            return copy
        }
    }

    private func cancelActiveStreamLocally() {
        streamTask?.cancel()
        streamTask = nil
        timerTask?.cancel()
        timerTask = nil
        isStreaming = false
        isStopping = false
        streamingRunId = nil
    }

    private func ensureStreamingAssistantMessage(partialContent: String?) -> String {
        if let last = messages.last, last.role == .assistant, last.status == nil {
            mutateMessage(id: last.id) { msg in
                var copy = msg
                copy.isStreaming = true
                return copy
            }
            return last.id
        }
        let id = "local_resume_\(UUID().uuidString)"
        var message = AgentChatMessage(
            id: id,
            turnId: nil,
            role: .assistant,
            content: "",
            thinking: nil,
            createdAt: Date(),
            status: nil,
            durationSeconds: nil,
            tools: [],
            blocks: [],
            proactive: false,
            usage: nil,
            isStreaming: true
        )
        if let partialContent, !partialContent.isEmpty {
            message = AgentTimelineBuilder.appendText(message, chunk: partialContent)
        }
        messages.append(message)
        return id
    }

    private func setPendingApproval(_ approval: AgentPendingApproval) {
        pendingApproval = approval
        var initial: [String: AgentPermissionDecisionInput] = [:]
        for tool in approval.tools {
            initial[tool.toolCallId] = AgentPermissionDecisionInput(
                toolCallId: tool.toolCallId,
                approved: true,
                rememberForSession: false
            )
        }
        approvalSelections = initial
        if approval.tools.contains(where: { $0.toolName == "plan_exit" }) {
            Task { await loadPlan() }
        }
    }

    private func clearDraftAttachmentsAndPanels() {
        draftGeneration += 1
        recordSearchGeneration += 1
        recordSearchTask?.cancel()
        recordSearchTask = nil
        attachedImages = []
        isProcessingImages = false
        selectedRecordReferences = []
        isPlusMenuOpen = false
        isRecordPickerOpen = false
        recordSearchQuery = ""
        recordReferenceOptions = []
        isLoadingRecordReferences = false
        isLoadingMoreRecordReferences = false
        recordReferenceError = nil
        recordReferencesHasMore = false
        recordReferencesNextOffset = 0
    }

    private func fetchRecordPage(
        query: String,
        offset: Int,
        append: Bool,
        expectedSearchGen: Int,
        expectedDraftGen: Int
    ) async {
        if append {
            isLoadingMoreRecordReferences = true
        } else {
            isLoadingRecordReferences = true
            recordReferenceError = nil
        }
        defer {
            if recordSearchGeneration == expectedSearchGen && draftGeneration == expectedDraftGen {
                if append {
                    isLoadingMoreRecordReferences = false
                } else {
                    isLoadingRecordReferences = false
                }
            }
        }

        do {
            let page = try await service.fetchRecordReferences(query: query, limit: 30, offset: offset)
            guard recordSearchGeneration == expectedSearchGen, draftGeneration == expectedDraftGen else { return }
            if append {
                let existingIds = Set(recordReferenceOptions.map(\.id))
                let fresh = page.items.filter { !existingIds.contains($0.id) }
                recordReferenceOptions.append(contentsOf: fresh)
            } else {
                recordReferenceOptions = page.items
            }
            cacheRecordMetadata(page.items)
            recordReferencesHasMore = page.hasMore
            recordReferencesNextOffset = page.nextOffset
            recordReferenceError = nil
        } catch {
            guard recordSearchGeneration == expectedSearchGen, draftGeneration == expectedDraftGen else { return }
            if !append {
                recordReferenceOptions = []
                recordReferencesHasMore = false
                recordReferencesNextOffset = 0
            }
            recordReferenceError = Self.userFacingError(error)
        }
    }

    private func attachingInMemoryImages(
        to mapped: [AgentChatMessage],
        sessionId: String
    ) -> [AgentChatMessage] {
        guard let entries = sessionInMemoryImages[sessionId], !entries.isEmpty else {
            return mapped
        }
        var result = mapped
        var usedUserIndices: Set<Int> = []

        for entry in entries.reversed() {
            guard let matchIndex = result.indices.reversed().first(where: { idx in
                guard !usedUserIndices.contains(idx), result[idx].role == .user else {
                    return false
                }
                let content = result[idx].content.trimmingCharacters(in: .whitespacesAndNewlines)
                if content == entry.localDisplayContent {
                    return true
                }
                if entry.isImageOnly && content.hasPrefix("用户发送了一张图片") {
                    return true
                }
                return false
            }) else {
                continue
            }
            usedUserIndices.insert(matchIndex)
            result[matchIndex].images = entry.images
        }
        return result
    }

    private func cacheRecordMetadata(_ items: [AgentRecordReferenceOption]) {
        for item in items {
            let key = item.displayTitle.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard !key.isEmpty else { continue }
            recordMetadataByNormalizedTitle[key] = item
        }
    }

    private func mutateMessage(id: String, transform: (AgentChatMessage) -> AgentChatMessage) {
        guard let idx = messages.firstIndex(where: { $0.id == id }) else { return }
        messages[idx] = transform(messages[idx])
    }

    private func upsertSubagentNotice(_ progress: AgentSubagentProgress) {
        if let idx = subagentNotices.firstIndex(where: { $0.source == progress.source }) {
            subagentNotices[idx] = progress
        } else {
            subagentNotices.append(progress)
        }
    }

    private static func userFacingError(_ error: Error) -> String {
        if let apiError = error as? APIClientError, let desc = apiError.errorDescription {
            return desc
        }
        return error.localizedDescription
    }
}
