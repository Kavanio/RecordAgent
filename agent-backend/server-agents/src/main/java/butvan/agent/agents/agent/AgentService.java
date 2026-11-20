package butvan.agent.agents.agent;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.agent.permission.OrphanedToolCallRecovery;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.permission.PendingApprovalView;
import butvan.agent.agents.agent.permission.PermissionBatchDecisionRequest;
import butvan.agent.agents.agent.permission.PermissionDecisionRequest;
import butvan.agent.agents.agent.permission.PermissionDecisionResponse;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.agent.run.AgentRun;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.context.ConversationContextAssembler;
import butvan.agent.agents.context.ProfileMaintenanceScheduler;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.material.ChatMaterialRecorder;
import butvan.agent.agents.memory.MemoryTurnLifecycle;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.review.UserTurnActivityListener;
import butvan.agent.agents.routing.ToolCapabilityRouter;
import butvan.agent.agents.routing.ToolRoutingStateSynchronizer;
import butvan.agent.agents.security.AgentSecurity;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.AgentTurnResult;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.tool.ToolCapabilityCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 聊天流服务门面。
 *
 * <p>对外保持统一的会话运行、审批与取消入口；内部将准备、审批恢复、
 * 流式执行与 AgentScope 运行时适配拆分给专用协作者处理。</p>
 */
@Slf4j
@Service
public class AgentService {

    private MemoryTurnLifecycle memoryLifecycle = MemoryTurnLifecycle.disabled();
    private UserTurnActivityListener userTurnActivityListener;
    private ChatMaterialRecorder chatMaterialRecorder = ChatMaterialRecorder.noop();

    /** 仅 agents 模块运行时保持关闭 Adapter；不改变既有构造和聊天依赖。 */
    @Autowired(required = false)
    public void setMemoryLifecycle(MemoryTurnLifecycle lifecycle) {
        this.memoryLifecycle = lifecycle;
    }

    /** 注入可选的用户发言活动监听（如主动陪伴节律重置），非阻塞且异常不影响对话主流程。 */
    @Autowired(required = false)
    public void setUserTurnActivityListener(ObjectProvider<UserTurnActivityListener> provider) {
        this.userTurnActivityListener = (ownerId, sessionId, channel) -> {
            var listener = provider.getIfAvailable();
            if (listener != null) listener.onUserTurnRecorded(ownerId, sessionId, channel);
        };
    }

    /** 注入可选的对话素材持久化实现；图片落盘并入库后将唯一文件 ID 写入用户消息 transcript。 */
    @Autowired(required = false)
    public void setChatMaterialRecorder(ObjectProvider<ChatMaterialRecorder> provider) {
        this.chatMaterialRecorder = command -> {
            var recorder = provider.getIfAvailable();
            return recorder == null ? List.of() : recorder.recordUserImages(command);
        };
    }

    private final ModelHolder modelHolder;
    private final CurrentUserProvider currentUserProvider;
    private final SessionCatalogService sessionCatalogService;
    private final TranscriptService transcriptService;
    private final PendingApprovalStore pendingApprovalStore;
    private final ActiveAgentRunRegistry activeRunRegistry;
    private final AgentRuntimeAdapter runtimeAdapter;
    private final AgentStreamExecutor streamExecutor;
    private final AgentApprovalCoordinator approvalCoordinator;
    private final AgentTurnPreparation turnPreparation;

    @Autowired
    public AgentService(
            ModelHolder modelHolder,
            CurrentUserProvider currentUserProvider,
            SessionCatalogService sessionCatalogService,
            TranscriptService transcriptService,
            PendingApprovalStore pendingApprovalStore,
            AgentEventManager agentEventManager,
            AgentFactory agentFactory,
            AgentSecurity agentSecurity,
            AgentRunCompleter agentRunCompleter,
            AgentRunCheckpointService checkpointService,
            ConversationContextAssembler contextAssembler,
            ProfileMaintenanceScheduler profileMaintenanceScheduler,
            ActiveAgentRunRegistry activeRunRegistry,
            OrphanedToolCallRecovery orphanedToolCallRecovery,
            ToolCapabilityRouter toolCapabilityRouter,
            ToolCapabilityCatalog toolCapabilityCatalog,
            ToolRoutingStateSynchronizer toolRoutingStateSynchronizer
    ) {
        this.modelHolder = modelHolder;
        this.currentUserProvider = currentUserProvider;
        this.sessionCatalogService = sessionCatalogService;
        this.transcriptService = transcriptService;
        this.pendingApprovalStore = pendingApprovalStore;
        this.activeRunRegistry = activeRunRegistry;
        this.runtimeAdapter = new AgentRuntimeAdapter(
                agentFactory, agentSecurity, orphanedToolCallRecovery, toolRoutingStateSynchronizer);
        this.streamExecutor = new AgentStreamExecutor(
                modelHolder, sessionCatalogService, pendingApprovalStore, agentEventManager,
                agentRunCompleter, checkpointService, profileMaintenanceScheduler, runtimeAdapter);
        this.approvalCoordinator = new AgentApprovalCoordinator(
                modelHolder, sessionCatalogService, pendingApprovalStore, agentRunCompleter,
                checkpointService, activeRunRegistry, runtimeAdapter, streamExecutor);
        this.turnPreparation = new AgentTurnPreparation(
                modelHolder, sessionCatalogService, transcriptService, checkpointService,
                contextAssembler, toolCapabilityRouter, toolCapabilityCatalog, runtimeAdapter,
                streamExecutor, () -> memoryLifecycle, () -> userTurnActivityListener, () -> chatMaterialRecorder);
    }

    /**
     * 创建一次 HTTP 流对应的队列和生产虚拟线程。
     *
     * <p>此方法立即返回，不能在 Controller 线程中等待模型结果。</p>
     */
    public AgentStreamSession streamAgent(AgentUserCall request) {
        turnPreparation.validateIncomingCall(request);
        String userId = currentUserProvider.currentUserId();
        String runId = normalizeRunId(request.runId());
        sessionCatalogService.requireActive(request.sessionId());
        pendingApprovalStore.requireNoPending(userId, request.sessionId());

        AgentStreamSession streamSession = new AgentStreamSession(runId);
        activeRunRegistry.register(userId, request.sessionId(), streamSession);
        streamSession.queue().offer(new AgentStreamEvent.RunStarted(runId));
        try {
            Thread producer = Thread.startVirtualThread(
                    () -> produceEvents(request, userId, streamSession));
            streamSession.bindProducer(producer);
            return streamSession;
        } catch (RuntimeException exception) {
            activeRunRegistry.unregister(userId, request.sessionId(), streamSession);
            throw exception;
        }
    }

    private void produceEvents(AgentUserCall request, String userId, AgentStreamSession streamSession) {
        final AgentRun[] runHolder = new AgentRun[1];
        try {
            if (request == null) throw new IllegalArgumentException("聊天请求不能为空");
            if (!modelHolder.isInitialized()) {
                streamExecutor.putEvent(streamSession, new AgentStreamEvent.Failed("请先完成模型配置"));
                return;
            }

            AgentTurnPreparation.PreparedTurn prepared = turnPreparation.prepare(
                    request, userId, streamSession, run -> runHolder[0] = run);
            if (prepared.cancelled()) {
                streamExecutor.finishCancelled(prepared.run(), streamSession);
                return;
            }

            AgentRun run = prepared.run();
            streamExecutor.execute(
                    run,
                    List.of(run.currentUserMessage(prepared.input(), request.ragContexts(), request.images())),
                    streamSession,
                    approvalCoordinator::pauseForConfirmation
            );
        } catch (Exception e) {
            String sessionId = request == null ? null : request.sessionId();
            streamExecutor.handleStreamFailure(
                    runHolder[0], sessionId, streamSession, e, "Agent处理失败，请稍后重试");
        } finally {
            if (request != null) {
                activeRunRegistry.unregister(userId, request.sessionId(), streamSession);
            }
            AgentRun run = runHolder[0];
            streamSession.settle(run == null ? null : run.persistedResult(streamSession.runId()));
        }
    }

    /** 原子保存当前批次的全部审核决定，完成后由前端恢复原运行。 */
    public PermissionDecisionResponse decidePermissions(PermissionBatchDecisionRequest request) {
        return approvalCoordinator.decidePermissions(currentUserProvider.currentUserId(), request);
    }

    public PermissionDecisionResponse decidePermission(PermissionDecisionRequest request) {
        return approvalCoordinator.decidePermission(currentUserProvider.currentUserId(), request);
    }

    /** 自动批准某次权限确认并恢复运行（供已显式授权的全权限渠道使用）。 */
    public AgentStreamSession autoApproveAndResume(String sessionId, String approvalId) {
        return approvalCoordinator.autoApproveAndResume(
                currentUserProvider.currentUserId(), sessionId, approvalId);
    }

    /** 取消已暂停的精确轮次，不恢复模型或执行任何已请求工具。 */
    public AgentTurnResult cancelPendingApproval(String sessionId, String approvalId, String runId) {
        return approvalCoordinator.cancelPendingApproval(
                currentUserProvider.currentUserId(), sessionId, approvalId, runId);
    }

    /** 使用原 runId 恢复权限确认后的同一轮运行。 */
    public AgentStreamSession resumeAgent(String sessionId, String approvalId, String requestedRunId) {
        return approvalCoordinator.resumeAgent(
                currentUserProvider.currentUserId(), sessionId, approvalId, requireRunId(requestedRunId));
    }

    /** 查询当前用户在指定会话中等待处理的审批。 */
    public Optional<PendingApprovalView> currentPendingApproval(String sessionId) {
        return approvalCoordinator.currentPendingApproval(currentUserProvider.currentUserId(), sessionId);
    }

    /** 由当前用户取消指定会话中的精确 run。 */
    public ActiveAgentRunRegistry.CancelResult cancelRun(String sessionId, String runId) {
        sessionCatalogService.requireActive(sessionId);
        return activeRunRegistry.cancel(
                currentUserProvider.currentUserId(), sessionId, requireRunId(runId));
    }

    /** 读取当前会话的任务计划书。 */
    public String currentPlan(String sessionId) {
        sessionCatalogService.requireActive(sessionId);
        if (!modelHolder.isInitialized()) return "";
        return runtimeAdapter.readPlan(currentUserProvider.currentUserId(), sessionId);
    }

    /** 将主动问候消息写入指定会话的 Transcript 上下文，并同步到 AgentState 以便用户直接接着回复。 */
    public void appendProactiveMessageToSession(String sessionId, String content) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) return;
        String userId = currentUserProvider.currentUserId();
        sessionCatalogService.requireActive(sessionId);
        String normalized = content.strip();
        transcriptService.appendProactiveAssistantMessage(sessionId, normalized);
        sessionCatalogService.touch(sessionId, normalized);
        if (modelHolder.isInitialized()) {
            runtimeAdapter.appendAssistantMessageToState(userId, sessionId, normalized);
        }
    }

    private String normalizeRunId(String runId) {
        String normalized = runId == null || runId.isBlank()
                ? UUID.randomUUID().toString() : runId.strip();
        if (normalized.length() > 100 || !normalized.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("runId 格式不正确");
        }
        return normalized;
    }

    private String requireRunId(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        return normalizeRunId(runId);
    }
}
