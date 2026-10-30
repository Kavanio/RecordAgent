package butvan.agent.network.review.life;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.review.PersonalDeliveryPort;
import butvan.agent.network.review.life.LifeReviewModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 统一内容投递：意图先落盘，外部发送在事务外，未知不自动重试。 */
@Service
@RequiredArgsConstructor
public class LifeDeliveryService {
    private final LifeReviewRepository repository;
    private final LifeAuthorizationService authorization;
    private final ResetBarrier barrier;
    private final List<PersonalDeliveryPort> channels;
    private final Clock automationClock;
    private final PlatformTransactionManager transactions;

    /** 只允许当前授权生成的已完成版本；重试不会重新读取资料或重新生成。 */
    public void send(String owner, String contentId, boolean retry) {
        try (var lease = barrier.enter()) {
            var content = repository.content(owner, contentId).orElseThrow(() -> new IllegalArgumentException("复盘不存在"));
            var s = authorization.require(owner);
            if (!s.feishu() || channels.size() != 1 || !content.grantId().equals(s.grantId())
                    || content.settingsRevision() != s.revision() || !barrier.isCurrent(content.generation())
                    || !LifeOutreachPolicy.isDeliverable(content))
                throw new IllegalArgumentException("此版本不在当前飞书投递授权内");
            if (automationClock.instant().isBefore(content.availableAt())) throw new IllegalArgumentException("复盘已顺延，尚未到呈现时间");
            Delivery d = new TransactionTemplate(transactions).execute(tx -> {
                repository.delivery(new Delivery(UUID.randomUUID().toString(), owner, contentId, "FEISHU", "PENDING",
                        s.targetFingerprint(), "", 0, automationClock.instant()));
                return repository.deliveries(owner, contentId).stream().filter(v -> v.channel().equals("FEISHU")).findFirst().orElseThrow();
            });
            if (d.state().equals("SENT")) return;
            if (d.state().equals("UNKNOWN") || d.state().equals("SENDING"))
                throw new IllegalArgumentException("投递结果未知，请先人工核对；不会自动重发");
            if ((!d.state().equals("PENDING") && !retry) || d.attempts() >= 3)
                throw new IllegalArgumentException("请明确重试失败副本，最多三次发送尝试");
            if (!d.targetFingerprint().equals(s.targetFingerprint())) throw new IllegalArgumentException("原投递目标已变化");
            authorization.require(owner);
            if (!barrier.isCurrent(lease.generation())) throw new IllegalArgumentException("重置已开始，停止投递");
            if (!repository.deliveryState(d.id(), d.state(), "SENDING", "", automationClock.instant())) return;
            PersonalDeliveryPort.Attempt result;
            try { result = channels.getFirst().send(s.targetFingerprint(), d.id(), render(content)); }
            catch (RuntimeException e) { result = new PersonalDeliveryPort.Attempt("UNKNOWN", ""); }
            String state = result != null && Set.of("SENT", "FAILED").contains(result.state()) ? result.state() : "UNKNOWN";
            repository.deliveryState(d.id(), "SENDING", state, result == null || result.messageId() == null ? "" : result.messageId(), automationClock.instant());
        }
    }
    /** 只渲染持久化版本，材料不得在发送时重新采集。 */
    public static String render(Content c) {
        StringBuilder text = new StringBuilder(c.day() + " 生活复盘 · v" + c.version() + "\n");
        text.append(c.coverage()).append("\n");
        if (c.draft() != null) {
            c.draft().observations().forEach(o -> text.append("\n").append(o.text()).append("\n依据：").append(String.join("、", o.evidenceIds())));
            c.draft().actions().forEach(a -> text.append("\n建议：").append(a.text()).append("\n依据：").append(String.join("、", a.evidenceIds())));
        }
        c.questions().forEach(q -> text.append("\n").append(q));
        if (!c.message().isBlank()) text.append("\n").append(c.message());
        text.append("\n补充可发送 /review ").append(c.id()).append(" 补充内容；无需立即回复。");
        return text.toString();
    }
}
