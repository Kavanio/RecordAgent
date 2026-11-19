package butvan.agent.agents.agent;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.agent.permission.PendingApproval;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.permission.PendingApprovalView;
import butvan.agent.agents.agent.permission.PermissionBatchDecisionRequest;
import butvan.agent.agents.agent.permission.PermissionDecisionRequest;
import butvan.agent.agents.agent.permission.PermissionDecisionResponse;
import butvan.agent.agents.agent.permission.PermissionToolDecision;
import butvan.agent.agents.agent.permission.PermissionToolDto;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.agent.run.AgentRun;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.material.ReplyImages;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.AgentTurnResult;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 人工介入（HITL）权限审批与恢复协调器。
 *
 * <p>集中管理待确认工具批次的暂停、会话级记忆匹配、前端审批决定保存、
 * 取消待审轮次以及审批通过后的流恢复。</p>
 */
@Slf4j
@RequiredArgsConstructor
class AgentApprovalCoordinator {

    private final ModelHolder modelHolder;
    private final SessionCatalogService sessionCatalogService;
    private final PendingApprovalStore pendingApprovalStore;
    private final AgentRunCompleter agentRunCompleter;
    private final AgentRunCheckpointService checkpointService;
    private final ActiveAgentRunRegistry activeRunRegistry;
    private final AgentRuntimeAdapter runtimeAdapter;
    private final AgentStreamExecutor streamExecutor;

    /** 优先应用已经记住的决定，未解决的工具进入待确认批次。 */
    boolean pauseForConfirmation(AgentRun run, List<ToolUseBlock> askedTools, AgentStreamSession streamSession) {
        List<ConfirmResult> rememberResults = new ArrayList<>();
        List<ToolUseBlock> unresolved = new ArrayList<>();

        for (ToolUseBlock tool : askedTools) {
            Optional<Boolean> remembered = pendingApprovalStore.remembered(
                    run.userId(), run.sessionId(), tool);
            if (remembered.isPresent()) {
                // 已记住也要走 ConfirmResult，不能跳过 AgentScope 的状态恢复流程
                rememberResults.add(new ConfirmResult(remembered.get(), tool));
            } else {
                unresolved.add(tool);
            }
        }

        if (unresolved.isEmpty()) {
            Msg resumeMessage = runtimeAdapter.buildResumeMessage(rememberResults);
            streamExecutor.execute(run, List.of(resumeMessage), streamSession, this::pauseForConfirmation);
            return true;
        }

        // 已记住的结果也放入统一 PendingApproval，确保最终一次恢复包含整批工具的结果
        PendingApproval approval = new PendingApproval(run, askedTools, streamSession.runId());
        rememberResults.forEach(result -> approval.decide(
                result.getToolCall().getId(), result.isConfirmed()));
        pendingApprovalStore.save(approval);
        checkpointService.save(run);

        return streamExecutor.putEvent(
                streamSession,
                new AgentStreamEvent.PermissionRequired(
                        approval.approvalId(), approval.runId(), run.turnId(), approval.pendingTools())
        );
    }

    /** 原子保存当前批次的全部审核决定，完成后由前端恢复原运行。 */
    PermissionDecisionResponse decidePermissions(String userId, PermissionBatchDecisionRequest request) {
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

    PermissionDecisionResponse decidePermission(String userId, PermissionDecisionRequest request) {
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

    /** 自动批准当前批次的全部待确认工具并恢复流式执行。 */
    AgentStreamSession autoApproveAndResume(String userId, String sessionId, String approvalId) {
        PendingApproval approval = pendingApprovalStore.require(approvalId, userId, sessionId);
        approval.approveAll();
        log.info("自动批准工具权限：sessionId={}, approvalId={}, 工具数={}",
                sessionId, approvalId, approval.toolCount());
        return resumeAgent(userId, sessionId, approvalId, approval.runId());
    }

    /** 取消已暂停的精确轮次，不恢复模型或执行任何已请求工具。 */
    AgentTurnResult cancelPendingApproval(String userId, String sessionId, String approvalId, String runId) {
        sessionCatalogService.requireActive(sessionId);
        PendingApproval approval = pendingApprovalStore.claimForCancellation(approvalId, userId, sessionId, runId);
        if (!agentRunCompleter.tryComplete(approval.run(), TranscriptMessageDto.MessageStatus.CANCELLED)) {
            pendingApprovalStore.restoreAfterFailedResume(approval);
            throw new IllegalStateException("取消后的聊天记录保存失败");
        }
        return approval.run().persistedResult(runId);
    }

    /** 使用原 runId 恢复权限确认后的同一轮运行。 */
    AgentStreamSession resumeAgent(String userId, String sessionId, String approvalId, String runId) {
        PendingApproval approval = pendingApprovalStore.claimForResume(
                approvalId, userId, sessionId, runId);
        AgentStreamSession streamSession = new AgentStreamSession(runId);
        streamSession.queue().offer(new AgentStreamEvent.RunStarted(runId));
        Thread producer = Thread.ofVirtual().unstarted(() -> {
            try {
                if (!modelHolder.isInitialized()) {
                    agentRunCompleter.tryComplete(approval.run(), TranscriptMessageDto.MessageStatus.FAILED);
                    streamExecutor.putEvent(streamSession, new AgentStreamEvent.Failed("请先完成模型配置"));
                    return;
                }
                List<ConfirmResult> results = approval.toConfirmResults();
                ReplyImages images = approval.run().runtimeContext().get(ReplyImages.class);
                if (images != null && results.stream().anyMatch(result -> result.isConfirmed()
                        && ReplyImages.SCREEN_TOOL.equals(result.getToolCall().getName()))) {
                    images.approveScreenshot();
                }
                streamExecutor.execute(
                        approval.run(),
                        List.of(runtimeAdapter.buildResumeMessage(results)),
                        streamSession,
                        this::pauseForConfirmation
                );
            } catch (Exception exception) {
                streamExecutor.handleStreamFailure(
                        approval.run(), sessionId, streamSession, exception, "恢复 Agent 处理失败，请重新发起任务。");
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
    Optional<PendingApprovalView> currentPendingApproval(String userId, String sessionId) {
        sessionCatalogService.requireActive(sessionId);
        return pendingApprovalStore.current(userId, sessionId);
    }
}
