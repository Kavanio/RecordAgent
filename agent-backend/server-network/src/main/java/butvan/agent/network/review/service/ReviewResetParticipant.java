package butvan.agent.network.review.service;

import butvan.agent.agents.reset.ResetParticipant;
import butvan.agent.network.review.repository.ReviewRepository;
import butvan.agent.network.review.repository.ReviewCoachRepository;
import butvan.agent.network.review.model.ReviewModels.Settings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

/** 出厂重置保留人工目标，不能复用会删除目标的 erase 入口。 */
@Component
@RequiredArgsConstructor
public class ReviewResetParticipant implements ResetParticipant {
    private final ReviewRepository repository;
    private final ReviewCoachRepository coach;
    private final Clock automationClock;
    @Override public String id() { return "review"; }
    @Override @Transactional
    public void clean(String owner) {
        var now = automationClock.instant();
        repository.activeGrant(owner).ifPresent(grant -> repository.revoke(owner, grant.id(), grant.revision(), now));
        Settings old = repository.settings(owner);
        if (old.enabled()) repository.saveSettings(new Settings(owner, false, old.periods(), old.domains(),
                old.historyDays(), old.dailyLimit(), old.providerId(), old.modelName(), old.fingerprint(),
                old.revision() + 1, now), old.revision());
        coach.resetPersonalization(owner);
    }
}
