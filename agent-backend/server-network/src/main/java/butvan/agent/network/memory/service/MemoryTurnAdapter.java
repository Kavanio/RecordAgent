package butvan.agent.network.memory.service;

import butvan.agent.agents.agent.AgentUserCall;
import butvan.agent.agents.memory.*;
import butvan.agent.agents.memory.MemorySourceRegistry.Source;
import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 把可信聊天入口适配为资格凭据；不上传原文，不自行生成经历。 */
@Component
@RequiredArgsConstructor
public class MemoryTurnAdapter implements MemoryTurnLifecycle {
    private final MemoryService memory;
    private final MemorySyncService sync;
    private final TranscriptService transcript;
    private final SessionCatalogService sessions;
    private final ResetBarrier barrier;

    @Override public void admitted(AgentUserCall request, String owner, String turnId) {
        if (!memory.remoteAvailable() || !MemorySourcePolicy.eligible(request)) return;
        var session = sessions.requireActive(request.sessionId());
        if (session.kind() != SessionKind.GENERAL || session.projectId() != null) return;
        long generation = barrier.state().generation();
        var user = transcript.readMemoryTurn(request.sessionId(), turnId).user();
        if (user == null || !user.content().equals(request.content().strip())) return;
        memory.capture(new Source(owner, request.sessionId(), turnId, user.id(), user.createdAt(), request.memoryChannel(),
                MemorySourcePolicy.hash(user.content()), generation));
    }

    @Override public void completed(String owner, String sessionId, String turnId, MessageStatus status) {
        sync.completed(owner, sessionId, turnId);
    }
}
