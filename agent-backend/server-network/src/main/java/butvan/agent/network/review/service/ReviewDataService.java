package butvan.agent.network.review.service;

import butvan.agent.network.review.dto.ReviewDtos.*;
import butvan.agent.network.review.model.ReviewModels.Settings;
import butvan.agent.network.review.repository.ReviewRepository;
import butvan.agent.network.review.repository.ReviewCoachRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

/** 复盘数据的显式删除入口；先撤销授权并关闭偏好，随后清理正文，阻止并发采集使数据复活。 */
@Service
@RequiredArgsConstructor
public class ReviewDataService {
    private final ReviewRepository repository;
    private final ReviewCoachRepository coachRepository;
    private final Clock automationClock;

    @Transactional
    public EraseResult erase(String owner, EraseRequest input) {
        ReviewPolicy.requireOwner(owner);
        if (input == null || !Boolean.TRUE.equals(input.confirmed()) || input.expectedSettingsRevision() < 0)
            throw new IllegalArgumentException("请明确确认清除复盘摘要与全部目标");
        Settings old = repository.settings(owner);
        if (old.revision() != input.expectedSettingsRevision())
            throw new IllegalArgumentException("复盘配置已变化，请刷新后重新确认清除");
        var now = automationClock.instant();
        repository.activeGrant(owner).ifPresent(grant -> repository.revoke(owner, grant.id(), grant.revision(), now));
        Settings paused = new Settings(owner, false, old.periods(), old.domains(), old.historyDays(), old.dailyLimit(),
                old.providerId(), old.modelName(), old.fingerprint(), old.revision() + 1, now);
        repository.saveSettings(paused, old.revision());
        coachRepository.disableUnfrozenDeliveries(owner);
        int[] deleted = repository.eraseData(owner);
        return new EraseResult(deleted[0], deleted[1], paused.revision());
    }
}
