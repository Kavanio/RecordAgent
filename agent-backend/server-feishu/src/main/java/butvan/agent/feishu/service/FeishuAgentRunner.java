package butvan.agent.feishu.service;

import butvan.agent.agents.agent.AgentService;
import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.agent.event.AgentStreamEvent;
import butvan.agent.agents.session.AgentStreamSession;
import butvan.agent.agents.session.AgentTurnResult;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** 消费通用运行事件，但只返回已落盘的最终快照；审批恢复不改变轮次、runId 或截止时间。 */
@Slf4j
@Component
public class FeishuAgentRunner {
    private final AgentService agentService;
    private final LongSupplier nanoTime;

    @Autowired
    public FeishuAgentRunner(AgentService agentService) { this(agentService, System::nanoTime); }

    FeishuAgentRunner(AgentService agentService, LongSupplier nanoTime) {
        this.agentService = agentService; this.nanoTime = nanoTime;
    }

    /** 取消后继续消费直到生产者真正解注册，不能提前释放会话槽位或把部分正文当作成功。 */
    public Reply run(String sessionId, String runId, String turnId, String input, Duration timeout, Control control) {
        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        AgentStreamSession session = null;
        String pendingApprovalId = null;
        boolean interrupted = false;
        try {
            if (control.stopping()) return new Reply(null, control.reason(), "任务在执行前已停止。");
            session = agentService.streamAgent(new AgentUserCall(sessionId, input, input, List.of(), runId, turnId));
            while (true) {
                checkStop(session, deadline, control);
                AgentStreamEvent event;
                try {
                    event = session.queue().poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                    control.stop("shutdown");
                    continue;
                }
                if (event instanceof AgentStreamEvent.PermissionRequired permission) {
                    pendingApprovalId = permission.approvalId();
                    // 终态入队发生在 finally 解注册之前；必须先等待，消除自动恢复注册冲突。
                    awaitSettlement(session, deadline, control);
                    if (control.stopping()) {
                        AgentTurnResult stopped = agentService.cancelPendingApproval(sessionId, permission.approvalId(), runId);
                        return new Reply(stopped, control.reason(), "任务已停止。");
                    }
                    session = agentService.autoApproveAndResume(sessionId, permission.approvalId());
                    pendingApprovalId = null;
                    continue;
                }
                if (event instanceof AgentStreamEvent.ToolCall) control.stage("正在执行工具");
                else if (event instanceof AgentStreamEvent.ToolResult) control.stage("正在整理工具结果");
                else if (event instanceof AgentStreamEvent.TextDelta) control.stage("正在生成回答");

                if (event != null && event.isTerminal()) {
                    AgentTurnResult result = awaitSettlement(session, deadline, control);
                    String failure = event instanceof AgentStreamEvent.Failed failed ? failed.message() : "本次处理失败，请稍后重试。";
                    return reply(result, control, failure);
                }
                if (session.completion().toCompletableFuture().isDone() && session.queue().isEmpty()) {
                    return reply(awaitSettlement(session, deadline, control), control, "任务已结束，但未能取得已保存的最终结果。");
                }
            }
        } catch (RuntimeException exception) {
            log.error("飞书运行适配失败：runId={}, errorType={}", runId, exception.getClass().getSimpleName());
            if (session != null && !session.completion().toCompletableFuture().isDone()) {
                control.stop("failure");
                awaitSettlement(session, deadline, control);
            }
            if (pendingApprovalId != null) {
                // 自动恢复失败不能把待审批槽位遗留给下一条消息；取消不执行任何工具。
                AgentTurnResult stopped = agentService.cancelPendingApproval(sessionId, pendingApprovalId, runId);
                return new Reply(stopped, "failure", "权限恢复失败，本次任务已停止，请稍后重试。");
            }
            return new Reply(null, control.reason(), "本次处理失败，请检查桌面端任务状态后重试。");
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private Reply reply(AgentTurnResult result, Control control, String failure) {
        if (result != null && result.status() == MessageStatus.COMPLETED) {
            return new Reply(result, null, result.content());
        }
        if (result != null && result.status() == MessageStatus.CANCELLED) {
            return new Reply(result, control.reason(), "timeout".equals(control.reason()) ? "本次处理已超时，任务已停止。" : "任务已停止。");
        }
        return new Reply(result, control.reason(), failure);
    }

    private void checkStop(AgentStreamSession session, long deadline, Control control) {
        if (nanoTime.getAsLong() - deadline >= 0) control.stop("timeout");
        if (control.stopping() && !session.completion().toCompletableFuture().isDone()) session.requestCancellation();
    }

    private AgentTurnResult awaitSettlement(AgentStreamSession session, long deadline, Control control) {
        boolean interrupted = false;
        try {
            while (true) {
                checkStop(session, deadline, control);
                try {
                    return session.completion().toCompletableFuture().get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // 唯一允许的空等待：持续检查绝对截止时间和取消，不执行网络发送或额外模型调用。
                } catch (InterruptedException exception) {
                    interrupted = true;
                    control.stop("shutdown");
                } catch (ExecutionException exception) {
                    throw new IllegalStateException("Agent 收尾结果不可用");
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 控制命令只设置标志，真正的框架中断在运行消费线程执行，不阻塞长连接回调。 */
    public static final class Control {
        private final AtomicReference<String> reason = new AtomicReference<>();
        private volatile String stage = "正在处理";
        public void stop(String reason) { this.reason.compareAndSet(null, reason); }
        public boolean stopping() { return reason.get() != null; }
        public String reason() { return reason.get(); }
        public String stage() { return stage; }
        void stage(String stage) { this.stage = stage; }
    }

    /** result 非 null 表示通用运行层有已落盘的精确轮次结果。 */
    public record Reply(AgentTurnResult result, String stopReason, String text) { }
}
