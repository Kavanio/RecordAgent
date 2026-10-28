package butvan.agent.agents.agent;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.agent.permission.*;
import butvan.agent.agents.agent.run.AgentRun;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.context.ContextEnvelope;
import butvan.agent.agents.context.ContextRequest;
import butvan.agent.agents.context.ConversationContextAssembler;
import butvan.agent.agents.context.ProfileMaintenanceScheduler;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.agents.routing.ToolCapabilityRouter;
import butvan.agent.agents.routing.ToolRoutingDecision;
import butvan.agent.agents.routing.ToolRoutingRequest;
import butvan.agent.agents.routing.ToolRoutingStateSynchronizer;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.AgentTurnResult;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.security.AgentSecurity;
import butvan.agent.agents.tool.ToolCapabilityCatalog;
import butvan.agent.agents.usage.ModelIdentity;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.harness.agent.workspace.plan.PlanModeManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent 聊天流服务。
 *
 * <p>本类负责“应用会话 -> RuntimeContext -> AgentScope 事件流”的衔接；
 * Controller 只负责 SSE 协议，目录册和消息持久化分别交给专用服务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private butvan.agent.agents.memory.MemoryTurnLifecycle memoryLifecycle = butvan.agent.agents.memory.MemoryTurnLifecycle.disabled();

    /** 仅 agents 模块运行时保持关闭 Adapter；不改变既有构造和聊天依赖。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMemoryLifecycle(butvan.agent.agents.memory.MemoryTurnLifecycle lifecycle) { this.memoryLifecycle = lifecycle; }

    private final ModelHolder modelHolder;
    private final CurrentUserProvider currentUserProvider;
    private final SessionCatalogService sessionCatalogService;
    private final TranscriptService transcriptService;
    // 保存等待用户确认的批次，以及当前会话的精确授权记忆。
    private final PendingApprovalStore pendingApprovalStore;
    private final AgentEventManager agentEventManager;
    private final AgentFactory agentFactory;
    private final AgentSecurity agentSecurity;
    private final AgentRunCompleter agentRunCompleter;
    private final AgentRunCheckpointService checkpointService;
    private final ConversationContextAssembler contextAssembler;
    private final ProfileMaintenanceScheduler profileMaintenanceScheduler;
    private final ActiveAgentRunRegistry activeRunRegistry;
    private final OrphanedToolCallRecovery orphanedToolCallRecovery;

    /** toolCapabilityRouter：根据当前用户请求产生本轮 Jev 能力组决策。 */
    private final ToolCapabilityRouter toolCapabilityRouter;
    /** toolCapabilityCatalog：提供允许路由的能力组名称集合。 */
    private final ToolCapabilityCatalog toolCapabilityCatalog;
    /** toolRoutingStateSynchronizer：让模型可见 Schema 与会话级工具执行状态保持一致。 */
    private final ToolRoutingStateSynchronizer toolRoutingStateSynchronizer;

    /**
     * 创建一次 HTTP 流对应的队列和生产虚拟线程。
     *
     * <p>此方法立即返回，不能在 Controller 线程中等待模型结果。</p>
     */
    public AgentStreamSession streamAgent(AgentUserCall request) {
        if (request == null) throw new IllegalArgumentException("聊天请求不能为空");
        if (!request.images().isEmpty() && !modelHolder.supportsVision()) {
            throw new IllegalArgumentException("当前模型未标注图片识别能力，请在模型配置中开启或切换到视觉模型");
        }
        if (request.images().size() > AgentImages.MAX_COUNT
                || request.images().stream().mapToLong(AgentImageAttachment::sizeBytes).sum() > AgentImages.MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("每轮最多 4 张图片，总大小不能超过 12 MiB");
        }
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

    /**
     * 创建一次 HTTP 流对应的队列和生产虚拟线程
     * 此方法立即返回
     * @param request
     * @param streamSession
     */
    private void produceEvents(AgentUserCall request, String userId, AgentStreamSession streamSession) {
        AgentRun run = null;

        try {
            if (request == null) throw new IllegalArgumentException("聊天请求不能为空");
            if (!modelHolder.isInitialized()) {
                putEvent(streamSession, new AgentStreamEvent.Failed("请先完成模型配置"));
                return;
            }

            // 1. 防止客户端伪造或使用已删除的会话
            sessionCatalogService.requireActive(request.sessionId());
            String requestedContext = request.context() == null || request.context().isBlank()
                    ? request.content() : request.context();
            String input = requireContent(requestedContext);
            String displayContent = request.content() == null || request.content().isBlank()
                    ? input : request.content().strip();

            // 2. 用户消息只在初始化请求时保存一次，回复确认时不再重复保存
            String turnId = request.turnId() == null
                    ? transcriptService.appendUserMessage(request.sessionId(), displayContent)
                    : transcriptService.appendUserMessageWithTurnId(request.sessionId(), displayContent, request.turnId());
            try { memoryLifecycle.admitted(request, userId, turnId); }
            catch (RuntimeException exception) {
                log.warn("经历来源登记未完成，聊天继续：errorType={}", exception.getClass().getSimpleName());
            }
            ContextEnvelope contextEnvelope = contextAssembler.assemble(
                    new ContextRequest(userId, displayContent));
            RuntimeContext context = createRuntimeContext(userId, request.sessionId());
            context.put(ContextEnvelope.class, contextEnvelope);
            run = new AgentRun(request.sessionId(), userId, turnId, context);
            checkpointService.save(run);

            // 仅新用户轮次修复进程重启遗留调用；HITL 恢复必须让 AgentScope 原样消费 ConfirmResult。
            recoverOrphanedToolCalls(userId, request.sessionId());

            // 首 token 前收到停止请求时，也要保留用户消息和 CANCELLED assistant 终态。
            if (streamSession.isCancelled()) {
                finishCancelled(run, streamSession);
                return;
            }

            // 当前用户轮次的 Jev 路由结果；只存入本轮 RuntimeContext
            ToolRoutingDecision routingDecision = toolCapabilityRouter.route(
                    new ToolRoutingRequest(displayContent, toolCapabilityCatalog.groupNames())
            );
            // 当前 AgentRun 独享的运行上下文；以类型作为决策的读取键
            context.put(ToolRoutingDecision.class, routingDecision);
            emitRoutingNotice(routingDecision, streamSession);
            synchronizeToolRoutingState(userId, request.sessionId(), routingDecision);
            // 当前 SSE 运行会话；路由期间用户也可能发出取消请求
            if (streamSession.isCancelled()) {
                // 已经建立 checkpoint 的当前 AgentRun，用现有取消流程统一收尾
                finishCancelled(run, streamSession);
                return;
            }

            // 3. 初始调用将用户消息交给 AgentScope；后续回复会传入确认消息
            runAgentStream(
                    run,
                    List.of(run.currentUserMessage(input, request.ragContexts(), request.images())),
                    streamSession
            );
        } catch (Exception e) {
            // 客户端已断开，按照取消收尾
            if (streamSession.isCancelled() || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                if (run != null) {
                    finishCancelled(run, streamSession);
                } else {
                    streamSession.offerTerminal(new AgentStreamEvent.Cancelled(streamSession.runId()));
                }
                return;
            }

            // 处理失败：按照失败收尾并回传安全错误文案
            String sessionId = request == null ? null : request.sessionId();
            log.error("Agent 流处理失败：sessionId={}",sessionId,e);
            String userMessage = e instanceof IllegalArgumentException ? e.getMessage() : "Agent处理失败，请稍后重试";
            if (run != null && isWaitingForPermission(run, e)) {
                userMessage = "该会话仍有操作等待桌面端权限确认，请现在 ButvanAgent 桌面端处理后再继续";
            }
            if (run != null && !agentRunCompleter.tryComplete(
                    run, TranscriptMessageDto.MessageStatus.FAILED)) {
                userMessage = terminalPersistenceFailureMessage(false);
            }
            putEvent(streamSession, new AgentStreamEvent.Failed(userMessage));
        } finally {
            if (request != null) {
                activeRunRegistry.unregister(userId, request.sessionId(), streamSession);
            }
            streamSession.settle(run == null ? null : run.persistedResult(streamSession.runId()));
        }
    }

    private void recoverOrphanedToolCalls(String userId, String sessionId) {
        HarnessAgent agent = agentFactory.currentAgent(sessionId);
        int recovered = orphanedToolCallRecovery.recover(
                agent.getDelegate().getAgentState(userId, sessionId), agent.getName());
        if (recovered > 0) {
            agent.getDelegate().saveAgentState(userId, sessionId);
            log.warn("已安全收尾进程遗留工具调用：sessionId={}, 数量={}", sessionId, recovered);
        }
    }

    /** 将 ACTIVE 路由决策写入当前会话状态，并在模型调用前持久化。 */
    private void synchronizeToolRoutingState(
            String userId,
            String sessionId,
            ToolRoutingDecision decision
    ) {
        if (decision == null || !decision.appliesToModelCall()) return;

        HarnessAgent agent = agentFactory.currentAgent(sessionId);
        var state = agent.getDelegate().getAgentState(userId, sessionId);
        if (toolRoutingStateSynchronizer.apply(state, decision)) {
            agent.getDelegate().saveAgentState(userId, sessionId);
        }
    }

    /** Jev 降级时发送非终态提示，使聊天继续执行且桌面端可以告知用户。 */
    private void emitRoutingNotice(
            ToolRoutingDecision decision,
            AgentStreamSession streamSession
    ) {
        if (decision == null
                || decision.status() != ToolRoutingDecision.Status.FALLBACK
                || decision.failure() == null) {
            return;
        }
        var failure = decision.failure();
        putEvent(streamSession, new AgentStreamEvent.RoutingNotice(
                failure.code().payloadValue(),
                failure.message(),
                failure.requestId(),
                failure.retryable(),
                failure.retryAfterMillis()
        ));
    }

    /**
     * 判断失败是否由“待确认的权限审批”引起。
     *
     * <p>优先查待确认存储；再兼容框架 paused for human-in-the-loop 异常信息，
     * 避免会话残留 ASKING 状态时向用户暴露框架异常。</p>
     */
    private boolean isWaitingForPermission(AgentRun run, Exception e) {
        if (pendingApprovalStore.hasPending(run.userId(), run.sessionId())) {
            return true;
        }
        return e instanceof IllegalStateException
                && e.getMessage() != null
                && e.getMessage().contains("paused for human-in-the-loop");
    }

    /**
     * 消费 AgentScope 事件流
     * 先识别 RequireUserConfirmEvent 再走普通映射
     * @param run
     * @param inputMessages
     * @param streamSession
     */
    private void runAgentStream(AgentRun run, List<Msg> inputMessages, AgentStreamSession streamSession) {
        HarnessAgent agent = agentFactory.currentAgent(run.sessionId());
        streamSession.bindCancellationAction(
                () -> agent.getDelegate().interrupt(run.userId(), run.sessionId()));
        ModelIdentity modelIdentity = currentModelIdentity();
        SessionPermissionMode productMode = sessionCatalogService.getPermissionMode(run.sessionId());
        agent.getDelegate().getAgentState(run.userId(), run.sessionId())
                .setPermissionContext(agentSecurity.createPermissionContext(productMode));
        // setPermissionMode 同时重建当前会话的权限引擎并持久化新的上下文。
        agent.setPermissionMode(run.userId(), run.sessionId(), toAgentScopeMode(productMode));

        for (AgentEvent event : agent.streamEvents(inputMessages,
                run.runtimeContext()).toIterable()) {
            // Token usage 必须在 UI 映射之前采集，包含工具循环和子 Agent 原始事件。
            run.recordModelEvent(event, modelIdentity);

            // 客户端断开：立即按取消收尾
            if (streamSession.isCancelled()) {
                finishCancelled(run, streamSession);
                return;
            }

            // 必须在普通映射之前识别该事件
            if (event instanceof RequireUserConfirmEvent confirmEvent) {
                if (pauseForConfirmation(run, confirmEvent.getToolCalls(), streamSession)) {
                    return;
                }
                continue;
            }

            AgentStreamEvent mapped = agentEventManager.map(event, run.toolArgsBuffer());
            run.record(mapped);
            if (event instanceof ModelCallEndEvent) {
                checkpointService.save(run);
            }
            if (mapped != null && mapped.isTerminal()) {
                // 先持久化再发送终态，确保前端收到 done 后能立即读取完整消息与 usage。
                if (agentRunCompleter.tryComplete(run, TranscriptMessageDto.MessageStatus.COMPLETED)) {
                    profileMaintenanceScheduler.consider(run.userId());
                    putEvent(streamSession, mapped);
                } else {
                    putEvent(streamSession,
                            new AgentStreamEvent.Failed(terminalPersistenceFailureMessage(false)));
                }
                return;
            }
            if (mapped != null && !putEvent(streamSession, mapped)) {
                finishCancelled(run, streamSession);
                return;
            }
        }

        if (streamSession.isCancelled()) {
            finishCancelled(run, streamSession);
            return;
        }

        // 事件流自然结束：正常收尾
        if (agentRunCompleter.tryComplete(run, TranscriptMessageDto.MessageStatus.COMPLETED)) {
            profileMaintenanceScheduler.consider(run.userId());
            putEvent(streamSession, new AgentStreamEvent.Completed());
        } else {
            putEvent(streamSession,
                    new AgentStreamEvent.Failed(terminalPersistenceFailureMessage(false)));
        }
    }

    /** 将产品文案稳定映射到 AgentScope 的运行模式。 */
    private PermissionMode toAgentScopeMode(SessionPermissionMode mode) {
        return switch (mode) {
            case ASK -> PermissionMode.DEFAULT;
            case AUTO_EDIT -> PermissionMode.ACCEPT_EDITS;
            case FULL_ACCESS -> PermissionMode.BYPASS;
        };
    }

    /**
     * 优先应用已经记住的决定，未解决的工具进入待确认批次
     * @param run
     * @param askedTools
     * @param streamSession
     * @return
     */
    private boolean pauseForConfirmation(AgentRun run, List<ToolUseBlock> askedTools, AgentStreamSession streamSession) {
        List<ConfirmResult> rememberResults = new ArrayList<>();
        List<ToolUseBlock> unresolved = new ArrayList<>();

        for (ToolUseBlock tool : askedTools) {
            Optional<Boolean> remembered = pendingApprovalStore.remembered(
                    run.userId(),
                    run.sessionId(),
                    tool
            );
            if (remembered.isPresent()) {
                // 已记住也要走 ConfirmResult，不能跳过 AgentScope 的状态恢复流程
                rememberResults.add(new ConfirmResult(remembered.get(), tool));
            } else {
                unresolved.add(tool);
            }
        }

        if (unresolved.isEmpty()) {
            Msg resumeMessage = buildResumeMessage(rememberResults);
            runAgentStream(run, List.of(resumeMessage), streamSession);
            return true;
        }

        // 已记住的结果也放入统一 PendingApproval，确保最终一次恢复包含整批工具的结果
        PendingApproval approval = new PendingApproval(run, askedTools, streamSession.runId());
        rememberResults.forEach(result -> approval.decide(
                result.getToolCall().getId(), result.isConfirmed()
        ));
        pendingApprovalStore.save(approval);
        checkpointService.save(run);

        return putEvent(
                streamSession,
                new AgentStreamEvent.PermissionRequired(
                        approval.approvalId(), approval.runId(), run.turnId(), approval.pendingTools())
        );
    }

    /** 原子保存当前批次的全部审核决定，完成后由前端恢复原运行。 */
    public PermissionDecisionResponse decidePermissions(PermissionBatchDecisionRequest request) {
        String userId = currentUserProvider.currentUserId();
        sessionCatalogService.requireActive(request.sessionId());
        PendingApproval approval = pendingApprovalStore.require(
                request.approvalId(), userId, request.sessionId());
        approval.decideBatch(request.decisions());

        for (PermissionToolDecision decision : request.decisions()) {
            if (decision.rememberForSession()) {
                ToolUseBlock tool = approval.findTool(decision.toolCallId());
                pendingApprovalStore.remember(
                        userId, request.sessionId(), tool, decision.approved());
            }
        }
        return PermissionDecisionResponse.ready();
    }

    public PermissionDecisionResponse decidePermission(PermissionDecisionRequest request) {
        String userId = currentUserProvider.currentUserId();
        sessionCatalogService.requireActive(request.sessionId());
        PendingApproval approval = pendingApprovalStore.require(
                request.approvalId(), userId, request.sessionId());

        approval.decide(request.toolCallId(), request.approved());

        if (request.rememberForSession()) {
            ToolUseBlock tool = approval.findTool(request.toolCallId());
            // 记住的仅是这一组完全相同的参数；不写 YAML，不影响其他会话。
            pendingApprovalStore.remember(userId, request.sessionId(), tool, request.approved());
        }

        PermissionToolDto next = approval.nextTool();
        return next == null ? PermissionDecisionResponse.ready()
                : PermissionDecisionResponse.next(next);
    }

    /**
     * 自动批准某次权限确认并恢复运行（供已显式授权的全权限渠道使用）。
     *
     * @param sessionId  会话 ID
     * @param approvalId 待确认批次 ID
     * @return 恢复后的 Agent 流
     */
    public AgentStreamSession autoApproveAndResume(String sessionId, String approvalId) {
        String userId = currentUserProvider.currentUserId();
        PendingApproval approval = pendingApprovalStore.require(approvalId, userId, sessionId);
        approval.approveAll();
        log.info("自动批准工具权限：sessionId={}, approvalId={}, 工具数={}",
                sessionId, approvalId, approval.toolCount());
        return resumeAgent(sessionId, approvalId, approval.runId());
    }

    /** 取消已暂停的精确轮次，不恢复模型或执行任何已请求工具。 */
    public AgentTurnResult cancelPendingApproval(String sessionId, String approvalId, String runId) {
        String userId = currentUserProvider.currentUserId();
        sessionCatalogService.requireActive(sessionId);
        PendingApproval approval = pendingApprovalStore.claimForCancellation(approvalId, userId, sessionId, runId);
        if (!agentRunCompleter.tryComplete(approval.run(), TranscriptMessageDto.MessageStatus.CANCELLED)) {
            pendingApprovalStore.restoreAfterFailedResume(approval);
            throw new IllegalStateException("取消后的聊天记录保存失败");
        }
        return approval.run().persistedResult(runId);
    }

    /**
     * 构建携带 ConfirmResult 的恢复消息，不是用户新提问
     * @param results
     * @return
     */
    private Msg buildResumeMessage(List<ConfirmResult> results) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, results);

        // 这是框架恢复信号，不是用户的新提问。
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent("permission confirmation received")
                .metadata(metadata)
                .build();
    }

    /** 使用原 runId 恢复权限确认后的同一轮运行。 */
    public AgentStreamSession resumeAgent(String sessionId, String approvalId, String requestedRunId) {
        String userId = currentUserProvider.currentUserId();
        String runId = requireRunId(requestedRunId);
        PendingApproval approval = pendingApprovalStore.claimForResume(
                approvalId, userId, sessionId, runId);
        AgentStreamSession streamSession = new AgentStreamSession(runId);
        streamSession.queue().offer(new AgentStreamEvent.RunStarted(runId));
        Thread producer = Thread.ofVirtual().unstarted(() -> {
            try {
                if (!modelHolder.isInitialized()) {
                    agentRunCompleter.tryComplete(approval.run(), TranscriptMessageDto.MessageStatus.FAILED);
                    putEvent(streamSession, new AgentStreamEvent.Failed("请先完成模型配置"));
                    return;
                }
                runAgentStream(approval.run(),
                        List.of(buildResumeMessage(approval.toConfirmResults())), streamSession);
            } catch (Exception exception) {
                if (streamSession.isCancelled() || Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    finishCancelled(approval.run(), streamSession);
                } else {
                    log.error("恢复 Agent 流失败: sessionId={}, approvalId={}", sessionId, approvalId, exception);
                    String message = agentRunCompleter.tryComplete(
                            approval.run(), TranscriptMessageDto.MessageStatus.FAILED)
                            ? "恢复 Agent 处理失败，请重新发起任务。"
                            : terminalPersistenceFailureMessage(false);
                    putEvent(streamSession, new AgentStreamEvent.Failed(message));
                }
            } finally {
                activeRunRegistry.unregister(userId, sessionId, streamSession);
                streamSession.settle(approval.run().persistedResult(streamSession.runId()));
            }
        });
        try {
            activeRunRegistry.register(userId, sessionId, streamSession);
            streamSession.bindProducer(producer);
            producer.start();
        } catch (RuntimeException exception) {
            activeRunRegistry.unregister(userId, sessionId, streamSession);
            pendingApprovalStore.restoreAfterFailedResume(approval);
            throw exception;
        }
        return streamSession;
    }

    /** 查询当前用户在指定会话中等待处理的审批。 */
    public Optional<PendingApprovalView> currentPendingApproval(String sessionId) {
        sessionCatalogService.requireActive(sessionId);
        return pendingApprovalStore.current(currentUserProvider.currentUserId(), sessionId);
    }

    /** 由当前用户取消指定会话中的精确 run。 */
    public ActiveAgentRunRegistry.CancelResult cancelRun(String sessionId, String runId) {
        sessionCatalogService.requireActive(sessionId);
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        String normalizedRunId = runId.strip();
        if (normalizedRunId.length() > 100 || !normalizedRunId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("runId 格式不正确");
        }
        return activeRunRegistry.cancel(currentUserProvider.currentUserId(), sessionId, normalizedRunId);
    }

    /**
     * 读取当前会话的任务计划书
     * @param sessionId
     * @return
     */
    public String currentPlan(String sessionId) {
        // 复用会话有效性校验，防止读取已删除会话的文件
        sessionCatalogService.requireActive(sessionId);
        if (!modelHolder.isInitialized()) return "";
        HarnessAgent agent = agentFactory.currentAgent(sessionId);
        RuntimeContext context = createRuntimeContext(currentUserProvider.currentUserId(), sessionId);
        // 计划文件默认位于工作区 plans/PLAN.md
        String planPath = PlanModeManager.DEFAULT_PLAN_DIR + "/PLAN.md";
        return agent.getWorkspaceManager()
                .readManagedWorkspaceFileUtf8(context, planPath);
    }



    /** 只在此处构造 RuntimeContext，保证所有 Agent 调用都使用同一个用户身份规则。 */
    private RuntimeContext createRuntimeContext(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    /** 每段 Agent 流开始时固化模型身份，兼容权限暂停期间切换模型的情况。 */
    private ModelIdentity currentModelIdentity() {
        ModelSelector selector = modelHolder.getCurrentSelector();
        String vendor = selector == null ? null : selector.vendor();
        return new ModelIdentity(vendor, modelHolder.getModel().getModelName());
    }



    /** 校验用户输入，避免空消息和异常大的请求进入模型。 */
    private String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        String normalized = content.strip();
        if (normalized.length() > 20_000) {
            throw new IllegalArgumentException("消息内容不能超过 20000 个字符");
        }
        return normalized;
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

    /** 先持久化 partial assistant，再将取消终态交给 SSE。 */
    private void finishCancelled(AgentRun run, AgentStreamSession streamSession) {
        // Thread.interrupt 会让 Files.lines 直接抛出 ClosedByInterruptException；
        // 取消只应终止模型/工具，不应打断最后一次 transcript 收尾。
        boolean interrupted = Thread.interrupted();
        try {
            if (agentRunCompleter.tryComplete(run, TranscriptMessageDto.MessageStatus.CANCELLED)) {
                streamSession.offerTerminal(new AgentStreamEvent.Cancelled(streamSession.runId()));
            } else {
                streamSession.offerTerminal(
                        new AgentStreamEvent.Failed(terminalPersistenceFailureMessage(true)));
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private String terminalPersistenceFailureMessage(boolean stopped) {
        return stopped
                ? "Agent 已停止，但聊天记录保存失败。请检查磁盘空间与数据目录权限后重试。"
                : "Agent 已结束，但聊天记录保存失败。请检查磁盘空间与数据目录权限后重试。";
    }


    /**
     * 事件入队
     * 客户端断开触发中断时立即停止生产
     * @param streamSession
     * @param event
     * @return
     */
    private boolean putEvent(AgentStreamSession streamSession, AgentStreamEvent event) {
        if (event.isTerminal()) return streamSession.offerTerminal(event);
        try {
            streamSession.queue().put(event);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }


}
