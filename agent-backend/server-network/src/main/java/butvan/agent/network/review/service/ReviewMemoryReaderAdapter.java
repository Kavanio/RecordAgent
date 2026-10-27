package butvan.agent.network.review.service;

import butvan.agent.agents.review.ReviewGenerationRequest;
import butvan.agent.agents.review.ReviewMemoryReader;
import butvan.agent.network.review.repository.ReviewCoachRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** 权威长期记忆读取 Adapter；按 owner、有效状态和期限筛选，不复制聊天记忆文件。 */
@Service
@RequiredArgsConstructor
public class ReviewMemoryReaderAdapter implements ReviewMemoryReader {
    private final ReviewAuthorizationService authorization;
    private final ReviewGoalService goals;
    private final ReviewCoachRepository repository;
    private final Clock automationClock;

    /** 报告授权只允许后台复盘读取；后续聊天召回必须提供独立用途权限。 */
    @Override
    @Transactional(readOnly = true)
    public Snapshot readForAuthorizedReview(String owner) {
        authorization.requireEnabled(owner);
        var now = automationClock.instant();
        return new Snapshot(
                goals.list(owner).stream().filter(goal -> "ACTIVE".equals(goal.state()))
                        .map(goal -> new ReviewGenerationRequest.Goal(goal.id(), goal.title(),
                                goal.constraintText(), goal.successCriterion())).toList(),
                repository.recallActions(owner, now).stream().map(item -> new ReviewGenerationRequest.HistoryAction(
                        item.id(), item.topicKey(), item.title(), item.state(),
                        item.checkOn() == null ? "" : item.checkOn().toString(), item.feedback())).toList(),
                repository.recallObservations(owner, now).stream().map(item -> new ReviewGenerationRequest.Observation(
                        item.id(), item.topicKey(), item.content(), item.uncertainty())).toList());
    }
}
