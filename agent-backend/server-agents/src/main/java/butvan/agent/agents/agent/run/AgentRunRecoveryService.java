package butvan.agent.agents.agent.run;

import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 应用启动后把上次进程遗留的轮次恢复为可见的取消消息。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunRecoveryService {

    private final AgentRunCheckpointService checkpointService;
    private final TranscriptService transcriptService;
    private final SessionCatalogService sessionCatalogService;

    @org.springframework.beans.factory.annotation.Autowired
    private butvan.agent.agents.reset.ResetBarrier resetBarrier;

    /** 启动完成后执行恢复，避免构造阶段依赖尚未初始化的目录册。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        if (resetBarrier != null && resetBarrier.blocked()) return;
        for (AgentRunCheckpoint checkpoint : checkpointService.list()) {
            recover(checkpoint);
        }
    }

    private void recover(AgentRunCheckpoint checkpoint) {
        try {
            sessionCatalogService.requireActive(checkpoint.sessionId());
        } catch (IllegalArgumentException exception) {
            checkpointService.delete(checkpoint.turnId());
            return;
        }

        try {
            if (!transcriptService.hasAssistantMessage(checkpoint.sessionId(), checkpoint.turnId())) {
                long durationMillis = Math.max(0L, Duration.between(
                        checkpoint.startedAt(), checkpoint.updatedAt()).toMillis());
                transcriptService.appendAssistantMessage(
                        checkpoint.sessionId(),
                        checkpoint.turnId(),
                        checkpoint.content(),
                        checkpoint.thinking(),
                        TranscriptMessageDto.MessageStatus.CANCELLED,
                        durationMillis,
                        checkpoint.tools(),
                        checkpoint.usage()
                );
                sessionCatalogService.touch(checkpoint.sessionId(), checkpoint.content());
            }
            checkpointService.delete(checkpoint.turnId());
        } catch (RuntimeException exception) {
            log.error("恢复中断 Agent 轮次失败：sessionId={}, turnId={}",
                    checkpoint.sessionId(), checkpoint.turnId(), exception);
        }
    }
}
