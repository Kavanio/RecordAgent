package butvan.agent.network.usage.service;

import butvan.agent.agents.reset.ResetParticipant;
import butvan.agent.network.usage.repository.TokenUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 聊天统计是可重建投影，重置时与源会话一起清除，不删除系统用量。 */
@Component
@RequiredArgsConstructor
public class UsageResetParticipant implements ResetParticipant {
    private final TokenUsageRepository repository;
    @Override public String id() { return "chat-usage"; }
    @Override @Transactional public void clean(String owner) { repository.resetChat(owner); }
}
