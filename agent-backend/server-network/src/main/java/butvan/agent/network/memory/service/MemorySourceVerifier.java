package butvan.agent.network.memory.service;

import butvan.agent.agents.memory.MemorySourcePolicy;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.network.memory.repository.MemorySyncRepository.SourceRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 写入与召回共用的原文核对，保持相同的范围、消息指针、时间与哈希判断。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemorySourceVerifier {
    private final TranscriptService transcript;
    private final SessionCatalogService sessions;

    /** 只核对已知轮次，缺失、损坏或超限返回空；不从其他资料补造来源。 */
    public TranscriptService.TurnSnapshot read(SourceRow source) {
        try {
            var session = sessions.requireActive(source.session());
            if (session.kind() != SessionKind.GENERAL || session.projectId() != null) return null;
            var turn = transcript.readMemoryTurn(source.session(), source.turn());
            var original = turn.user();
            if (original == null || original.status() != MessageStatus.COMPLETED || !source.message().equals(original.id())
                    || !source.occurredAt().equals(original.createdAt()) || !MemorySourcePolicy.eligibleText(original.content())
                    || !source.hash().equals(MemorySourcePolicy.hash(original.content()))) return null;
            return turn;
        } catch (RuntimeException exception) {
            log.warn("经历来源不可核对，停止该来源：errorType={}", exception.getClass().getSimpleName());
            return null;
        }
    }
}
