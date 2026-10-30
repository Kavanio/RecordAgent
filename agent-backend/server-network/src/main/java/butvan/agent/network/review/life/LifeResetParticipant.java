package butvan.agent.network.review.life;

import butvan.agent.agents.reset.ResetParticipant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 重置清除生活复盘派生理解，冻结的外发副本独立只读保留。 */
@Component
@RequiredArgsConstructor
public class LifeResetParticipant implements ResetParticipant {
    private final LifeReviewRepository repository;
    @Override public String id() { return "life-review"; }
    @Override public String scopeVersion(String owner) { return "life-review-m4-m5-v1"; }
    @Override public List<String> additionalDeletes(String owner) {
        return List.of("生活复盘正文材料、补充、授权、主动候选与候选作息；已外发或结果未知副本转为只读存档保留");
    }
    @Override @Transactional public void clean(String owner) { repository.reset(owner); }
}
