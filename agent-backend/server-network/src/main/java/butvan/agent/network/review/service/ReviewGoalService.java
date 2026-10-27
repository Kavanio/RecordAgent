package butvan.agent.network.review.service;

import butvan.agent.network.review.dto.ReviewDtos.*;
import butvan.agent.network.review.model.ReviewModels.Goal;
import butvan.agent.network.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 用户明确目标的独立入口；不修改画像，不允许 AI 候选观察自动进入目标表。 */
@Service
@RequiredArgsConstructor
public class ReviewGoalService {
    private final ReviewRepository repository;
    private final Clock automationClock;

    @Transactional(readOnly = true)
    public List<GoalView> list(String owner) {
        ReviewPolicy.requireOwner(owner);
        return repository.goals(owner).stream().map(GoalView::from).toList();
    }

    /** 最多三个有效目标，以用户提供的完成标准为准；冲突拒绝覆盖。 */
    @Transactional
    public GoalView save(String owner, String id, SaveGoal input) {
        ReviewPolicy.requireOwner(owner);
        if (input == null || input.expectedRevision() < 0 || !Set.of("ACTIVE", "ARCHIVED").contains(input.state() == null ? "" : input.state()))
            throw new IllegalArgumentException("目标状态或版本无效");
        String title = text(input.title(), 200), constraint = text(input.constraintText(), 500), criterion = text(input.successCriterion(), 300);
        if (title.isBlank()) throw new IllegalArgumentException("请输入目标名称");
        Goal old = id == null ? null : repository.goal(owner, id).orElseThrow(() -> new IllegalArgumentException("目标不存在"));
        if (old == null && input.expectedRevision() != 0 || old != null && old.revision() != input.expectedRevision())
            throw new IllegalArgumentException("目标已变化，请刷新后重试");
        if (old == null && repository.goals(owner).size() >= 100) throw new IllegalArgumentException("最多保留 100 个目标");
        if ("ACTIVE".equals(input.state()) && (old == null || !"ACTIVE".equals(old.state())) && repository.activeGoalCount(owner) >= 3)
            throw new IllegalArgumentException("最多同时保留 3 个有效目标，请先归档旧目标");
        var now = automationClock.instant();
        Goal value = new Goal(old == null ? UUID.randomUUID().toString() : old.id(), owner, title, constraint, criterion,
                input.state(), input.expectedRevision() + 1, old == null ? now : old.createdAt(), now);
        repository.saveGoal(value, input.expectedRevision(), old == null);
        return GoalView.from(value);
    }

    private String text(String value, int limit) {
        String result = value == null ? "" : value.strip();
        if (result.length() > limit || result.indexOf('\0') >= 0) throw new IllegalArgumentException("目标内容过长或含无效字符");
        return result;
    }
}
