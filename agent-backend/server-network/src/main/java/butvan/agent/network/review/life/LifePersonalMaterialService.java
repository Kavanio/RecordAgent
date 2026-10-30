package butvan.agent.network.review.life;

import butvan.agent.agents.usage.TokenCounter;
import butvan.agent.network.review.service.ReviewGoalService;
import butvan.agent.network.review.life.LifeReviewModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 显式目标与用户反馈的独立可选来源；不借用旧复盘或聊天记忆许可。 */
@Service
@RequiredArgsConstructor
public class LifePersonalMaterialService {
    private final ReviewGoalService goals;
    private final LifeReviewRepository repository;
    private final TokenCounter counter;
    /** 目标最多300、反馈最多500本地估算Token；超预算完整跳过，不悄悄截去用户约束。 */
    public List<Material> collect(Settings s, Instant cutoff) {
        if (!s.sources().contains("GOALS")) return List.of();
        List<Material> result = new ArrayList<>();
        int goalTokens = 0, feedbackTokens = 0;
        for (var g : goals.list(s.owner()).stream().filter(g -> g.state().equals("ACTIVE")).limit(3).toList()) {
            String text = "用户明确目标：" + g.title() + "；约束：" + g.constraintText() + "；衡量：" + g.successCriterion();
            int tokens = counter.count(text) + 80;
            if (goalTokens + tokens > 300 || g.updatedAt().isAfter(cutoff)) continue;
            goalTokens += tokens;
            result.add(new Material("goal-" + g.id(), "USER_GOAL", g.id(), g.updatedAt().toString(), text, false));
        }
        for (var material : repository.feedbackMaterials(s.owner(), cutoff.minus(Duration.ofDays(s.historyDays())), cutoff)) {
            int tokens = counter.count(material.text()) + 80;
            if (feedbackTokens + tokens > 500) continue;
            feedbackTokens += tokens;
            result.add(material);
        }
        return List.copyOf(result);
    }
    /** 发布时重新核对当前目标和反馈，用户修正后不继续发布旧理解。 */
    public void verify(Settings s, List<Material> values) {
        if (values.stream().noneMatch(m -> m.kind().equals("USER_GOAL") || m.kind().equals("USER_FEEDBACK"))) return;
        var current = goals.list(s.owner()).stream().filter(g -> g.state().equals("ACTIVE")).toList();
        var candidates = repository.candidates(s.owner());
        for (var m : values) {
            if (m.kind().equals("USER_GOAL") && current.stream().noneMatch(g -> g.id().equals(m.sourceId()) && m.text().equals(
                    "用户明确目标：" + g.title() + "；约束：" + g.constraintText() + "；衡量：" + g.successCriterion())))
                throw new IllegalArgumentException("用户复盘目标已变化");
            if (m.kind().equals("USER_FEEDBACK") && candidates.stream().noneMatch(c -> c.id().equals(m.sourceId())
                    && m.text().equals("用户对主动主题 " + c.topic() + " 的明确反馈：" + c.feedback())))
                throw new IllegalArgumentException("用户复盘反馈已变化");
        }
    }
}
