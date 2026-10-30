package butvan.agent.network.review.life;

import butvan.agent.agents.review.LifeReviewReplyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;

/** 桌面与飞书补充归入同一内容主题，保留原版本而不重放聊天 Agent。 */
@Service
@RequiredArgsConstructor
public class LifeReplyService implements LifeReviewReplyPort {
    private final LifeAuthorizationService authorization;
    private final LifeReviewService reviews;
    private final LifeReviewRepository repository;
    private final Clock automationClock;
    @Override @Transactional
    public String reply(String owner, String contentId, String replyId, String text) {
        var s = authorization.require(owner);
        var c = (contentId == null || contentId.isBlank() || "latest".equals(contentId))
                ? repository.history(owner).stream()
                    .filter(item -> item.grantId().equals(s.grantId()) && item.settingsRevision() == s.revision())
                    .max(java.util.Comparator.comparing(LifeReviewModels.Content::day)
                            .thenComparingInt(LifeReviewModels.Content::version))
                    .orElseThrow(() -> new IllegalArgumentException("当前尚无可补充的生活复盘版本"))
                : reviews.requireContent(owner, contentId);
        if (!c.grantId().equals(s.grantId()) || c.settingsRevision() != s.revision()
                || text == null || text.isBlank() || text.length() > 1000
                || replyId == null || !replyId.matches("[a-zA-Z0-9-]{1,80}"))
            throw new IllegalArgumentException("补充内容或授权版本无效");
        if (!repository.reply(owner, replyId, c.id(), text.strip(), automationClock.instant()))
            return "这条补充已经记录，可在桌面查看对应版本。";
        var latest = repository.latest(owner, c.day()).orElseThrow();
        reviews.prepare(owner, new LifeReviewDtos.Prepare(c.day(), latest.version(), true));
        return "补充已记录，已安排新版本；已发副本保留，不会自动重复发送。";
    }
}
