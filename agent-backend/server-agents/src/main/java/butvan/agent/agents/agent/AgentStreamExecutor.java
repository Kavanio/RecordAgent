package butvan.agent.agents.agent;

import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.AgentRun;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.context.ProfileMaintenanceScheduler;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.model.ModelSelector;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.dto.SessionPermissionMode;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.agents.usage.ModelIdentity;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 流式事件消费与终态收尾执行器。
 *
 * <p>负责订阅 {@link AgentRuntimeAdapter} 产出的事件流，采集模型 Token 用量、
 * 持久化检查点、向 SSE 队列转发事件，并在取消、失败或自然结束时确保只写入一次终态。</p>
 */
@Slf4j
@RequiredArgsConstructor
class AgentStreamExecutor {

    @FunctionalInterface
    interface ConfirmationPauseHandler {
        boolean pause(AgentRun run, List<ToolUseBlock> askedTools, AgentStreamSession streamSession);
    }

    private final ModelHolder modelHolder;
    private final SessionCatalogService sessionCatalogService;
    private final PendingApprovalStore pendingApprovalStore;
    private final AgentEventManager agentEventManager;
    private final AgentRunCompleter agentRunCompleter;
    private final AgentRunCheckpointService checkpointService;
    private final ProfileMaintenanceScheduler profileMaintenanceScheduler;
    private final AgentRuntimeAdapter runtimeAdapter;

    /** 消费单次或恢复后的 AgentScope 事件流，直到终态或等待人工审批暂停。 */
    void execute(
            AgentRun run,
            List<Msg> inputMessages,
            AgentStreamSession streamSession,
            ConfirmationPauseHandler confirmationPauseHandler
    ) {
        ModelIdentity modelIdentity = currentModelIdentity();
        SessionPermissionMode productMode = sessionCatalogService.getPermissionMode(run.sessionId());

        for (AgentEvent event : runtimeAdapter.streamEvents(run, productMode, inputMessages, streamSession)) {
            // Token usage 必须在 UI 映射之前采集，包含工具循环和子 Agent 原始事件。
            run.recordModelEvent(event, modelIdentity);

            if (streamSession.isCancelled()) {
                finishCancelled(run, streamSession);
                return;
            }

            // 必须在普通映射之前识别权限确认事件
            if (event instanceof RequireUserConfirmEvent confirmEvent) {
                if (confirmationPauseHandler.pause(run, confirmEvent.getToolCalls(), streamSession)) {
                    return;
                }
                continue;
            }

            AgentStreamEvent mapped = agentEventManager.map(
                    event, run.toolArgsBuffer(), run.toolResultPrefixBuffer());
            run.record(mapped);
            if (event instanceof ModelCallEndEvent) {
                checkpointService.save(run);
            }
            if (mapped != null && mapped.isTerminal()) {
                completeWithTerminalEvent(run, streamSession, mapped);
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
        completeWithTerminalEvent(run, streamSession, new AgentStreamEvent.Completed());
    }

    /** 先持久化 partial assistant，再将取消终态交给 SSE。 */
    void finishCancelled(AgentRun run, AgentStreamSession streamSession) {
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

    /** 统一处理流执行异常：区分客户端取消与业务/权限失败并落盘终态。 */
    void handleStreamFailure(
            AgentRun run,
            String sessionId,
            AgentStreamSession streamSession,
            Exception exception,
            String fallbackFailureMessage
    ) {
        if (streamSession.isCancelled() || Thread.currentThread().isInterrupted()) {
            Thread.currentThread().interrupt();
            if (run != null) {
                finishCancelled(run, streamSession);
            } else {
                streamSession.offerTerminal(new AgentStreamEvent.Cancelled(streamSession.runId()));
            }
            return;
        }

        log.error("Agent 流处理失败：sessionId={}", sessionId, exception);
        String userMessage = exception instanceof IllegalArgumentException
                ? exception.getMessage()
                : fallbackFailureMessage;
        if (run != null && isWaitingForPermission(run, exception)) {
            userMessage = "该会话仍有操作等待桌面端权限确认，请现在 ButvanAgent 桌面端处理后再继续";
        }
        if (run != null && !agentRunCompleter.tryComplete(run, TranscriptMessageDto.MessageStatus.FAILED)) {
            userMessage = terminalPersistenceFailureMessage(false);
        }
        putEvent(streamSession, new AgentStreamEvent.Failed(userMessage));
    }

    boolean putEvent(AgentStreamSession streamSession, AgentStreamEvent event) {
        if (event.isTerminal()) return streamSession.offerTerminal(event);
        try {
            streamSession.queue().put(event);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    String terminalPersistenceFailureMessage(boolean stopped) {
        return stopped
                ? "Agent 已停止，但聊天记录保存失败。请检查磁盘空间与数据目录权限后重试。"
                : "Agent 已结束，但聊天记录保存失败。请检查磁盘空间与数据目录权限后重试。";
    }

    private void completeWithTerminalEvent(
            AgentRun run,
            AgentStreamSession streamSession,
            AgentStreamEvent terminalEvent
    ) {
        // 先持久化再发送终态，确保前端收到 done 后能立即读取完整消息与 usage。
        if (agentRunCompleter.tryComplete(run, TranscriptMessageDto.MessageStatus.COMPLETED)) {
            profileMaintenanceScheduler.consider(run.userId());
            putEvent(streamSession, terminalEvent);
        } else {
            putEvent(streamSession, new AgentStreamEvent.Failed(terminalPersistenceFailureMessage(false)));
        }
    }

    private ModelIdentity currentModelIdentity() {
        ModelSelector selector = modelHolder.getCurrentSelector();
        String vendor = selector == null ? null : selector.vendor();
        return new ModelIdentity(vendor, modelHolder.getModel().getModelName());
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
}
