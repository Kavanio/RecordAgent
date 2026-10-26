package butvan.agent.agents.agent;

import butvan.agent.agents.agent.run.AgentRun;
import butvan.agent.agents.agent.run.AgentRunCheckpointService;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Agent 流的终态收尾：在正常结束、失败或取消时仅追加一次完整 assistant 消息。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentRunCompleter {

    private final TranscriptService transcriptService;
    private final SessionCatalogService sessionCatalogService;
    private final AgentRunCheckpointService checkpointService;


    /**
     * 结束一次运行并落库
     * @param run
     * @param status
     */
    public void complete(AgentRun run, TranscriptMessageDto.MessageStatus status) {
        // 取消路径通常先中断生产线程；清掉本次中断标记，避免 Files.lines 等可中断 I/O
        // 在读取 transcript 时抛出 ClosedByInterruptException。收尾完成后恢复原标记。
        boolean interrupted = Thread.interrupted();
        try {
            completeInternal(run, status);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * 尝试写入终态，避免存储故障逃出异步生产线程。
     *
     * @return transcript 已存在或本次成功写入时返回 {@code true}
     */
    public boolean tryComplete(AgentRun run, TranscriptMessageDto.MessageStatus status) {
        try {
            complete(run, status);
            return true;
        } catch (RuntimeException exception) {
            log.error("Agent 终态持久化失败：sessionId={}, turnId={}, status={}",
                    run.sessionId(), run.turnId(), status, exception);
            return false;
        }
    }

    private void completeInternal(AgentRun run, TranscriptMessageDto.MessageStatus status) {
        if (!run.beginCompletion()) return;

        // 防止系统时钟微笑回拨产生负数
        long durationMillis = Math.max(0, Duration.between(run.startedAt(), Instant.now()).toMillis());

        try {
            // 固化工具状态并落库完整 assistant 消息
            transcriptService.appendAssistantMessage(
                    run.sessionId(),
                    run.turnId(),
                    run.contentAsString(),
                    run.thinkingAsString(),
                    status,
                    durationMillis,
                    run.finalizeTools(status),
                    run.tokenUsage()
            );
            run.commitCompletion(status);
        } catch (RuntimeException exception) {
            run.abortCompletion();
            throw exception;
        }

        try {
            checkpointService.delete(run.turnId());
        } catch (RuntimeException exception) {
            // transcript 已是权威终态；遗留检查点会在下次启动时按 turnId 去重并清理。
            log.warn("清理已完成 Agent 运行检查点失败：turnId={}", run.turnId(), exception);
        }

        // transcript 已是权威终态；目录册预览刷新失败不能推翻已完成状态。
        try {
            sessionCatalogService.touch(run.sessionId(), run.contentAsString());
        } catch (RuntimeException exception) {
            log.warn("刷新已完成 Agent 运行的会话摘要失败：sessionId={}, turnId={}",
                    run.sessionId(), run.turnId(), exception);
        }
    }
}
