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
        sendInternal(owner, contentId, "FEISHU", null, retry);
    }

    /** 主动候选投递：日报复用 FEISHU 主渠道，事件与恢复候选独立投递避免被已发日报幂等吞掉。 */
    public void sendCandidate(String owner, Candidate candidate) {
        if (candidate == null) return;
        if ("REVIEW".equals(candidate.eventKind())) {
            send(owner, candidate.contentId(), false);
            return;
        }
        boolean dailySent = repository.deliveries(owner, candidate.contentId()).stream()
                .anyMatch(d -> "FEISHU".equals(d.channel()) && !"PENDING".equals(d.state()));
        String channelKey = dailySent ? "FEISHU:" + candidate.id() : "FEISHU";
        String prefix = ("RECOVERY".equals(candidate.eventKind()) ? "【恢复跟进】" : "【事件提醒】")
                + candidate.topic() + "\n";
        sendInternal(owner, candidate.contentId(), channelKey, prefix, false);
    }

    private void sendInternal(String owner, String contentId, String channelKey, String prefix, boolean retry) {
        try (var lease = barrier.enter()) {
            var content = repository.content(owner, contentId).orElseThrow(() -> new IllegalArgumentException("复盘不存在"));
            var s = authorization.require(owner);
            if (!s.feishu() || channels.size() != 1 || !content.grantId().equals(s.grantId())
                    || content.settingsRevision() != s.revision() || !barrier.isCurrent(content.generation())
                    || !LifeOutreachPolicy.isDeliverable(content))
                throw new IllegalArgumentException("此版本不在当前飞书投递授权内");
            if (automationClock.instant().isBefore(content.availableAt()))
                throw new IllegalArgumentException("复盘已顺延，尚未到呈现时间");
            Delivery d = new TransactionTemplate(transactions).execute(tx -> {
                repository.delivery(new Delivery(UUID.randomUUID().toString(), owner, contentId, channelKey, "PENDING",
                        s.targetFingerprint(), "", 0, automationClock.instant()));
                return repository.deliveries(owner, contentId).stream()
                        .filter(v -> v.channel().equals(channelKey)).findFirst().orElseThrow();
            });
            if (d.state().equals("SENT")) return;
            if (d.state().equals("UNKNOWN") || d.state().equals("SENDING"))
                throw new IllegalArgumentException("投递结果未知，请先人工核对；不会自动重发");
            if ((!d.state().equals("PENDING") && !retry) || d.attempts() >= 3)
                throw new IllegalArgumentException("请明确重试失败副本，最多三次发送尝试");
            if (!d.targetFingerprint().equals(s.targetFingerprint()))
                throw new IllegalArgumentException("原投递目标已变化");
            authorization.require(owner);
            if (!barrier.isCurrent(lease.generation())) throw new IllegalArgumentException("重置已开始，停止投递");
            if (!repository.deliveryState(d.id(), d.state(), "SENDING", "", automationClock.instant())) return;
            String body = (prefix == null ? "" : prefix) + render(content);
            PersonalDeliveryPort.Attempt result;
            try { result = channels.getFirst().send(s.targetFingerprint(), d.id(), body); }
            catch (RuntimeException e) { result = new PersonalDeliveryPort.Attempt("UNKNOWN", ""); }
            String state = result != null && Set.of("SENT", "FAILED").contains(result.state()) ? result.state() : "UNKNOWN";
            repository.deliveryState(d.id(), "SENDING", state,
                    result == null || result.messageId() == null ? "" : result.messageId(), automationClock.instant());
        }
    }

    /** 只渲染持久化版本，材料不得在发送时重新采集，也不向外部输出内部证据编号与技术覆盖串。 */
    public static String render(Content c) {
        List<String> segments = new ArrayList<>();
        segments.add("跟你顺手理一下今天的复盘（" + c.day() + "）：");
        if (c.draft() != null) {
            for (var o : c.draft().observations()) {
                if (o.text() != null && !o.text().isBlank()) segments.add(o.text().strip());
            }
            List<String> actions = c.draft().actions() == null ? List.of() : c.draft().actions().stream()
                    .map(a -> a.text() == null ? "" : a.text().strip().replaceFirst("^建议[：:]\\s*", ""))
                    .filter(t -> !t.isEmpty()).toList();
            for (int i = 0; i < actions.size(); i++) {
                String lead = i == 0 ? "给你留个小建议：" : i == 1 ? "另外可以顺手试试：" : "还有一点：";
                segments.add(lead + actions.get(i));
            }
        }
        for (String q : c.questions()) {
            if (q != null && !q.isBlank()) segments.add(q.strip());
        }
        if (c.draft() == null && !c.message().isBlank()) segments.add(c.message().strip());
        return String.join("\n\n", segments);
    }
}
