package butvan.agent.network.review.life;

import butvan.agent.network.review.life.LifeReviewModels.*;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 有界主动策略：先判断值得呈现，再决定时机；不根据未回复推断睡眠或危险。 */
@Service
@RequiredArgsConstructor
public class LifeOutreachService {
    private final LifeReviewRepository repository;
    private final LifeAuthorizationService authorization;
    private final LifeDeliveryService deliveries;
    private final Clock automationClock;

    /** 读取当前候选及按最新作息计算的时机，不触发投递。 */
    public List<CandidateView> candidates(String owner) {
        return repository.candidates(owner).stream().map(c -> new CandidateView(c.id(), c.topic(), c.sourceId(),
                c.contentId(), c.state(), due(c), c.expiresAt(), c.feedback(), c.eventKind())).toList();
    }
    /** 读取或按明确用户输入纠正带有效期的作息候选。 */
    public HabitView habit(String owner) {
        return repository.habit(owner).map(h -> new HabitView(h.revision(), h.availableHour(), h.source(),
                h.uncertainty(), h.observedAt(), h.expiresAt(), h.active())).orElse(null);
    }
    @Transactional
    public HabitView habit(String owner, SaveHabit input) {
        authorization.require(owner);
        if (input == null || input.availableHour() < 12 || input.availableHour() > 23
                || input.source() == null || input.source().isBlank() || input.source().length() > 300)
            throw new IllegalArgumentException("请明确说明作息来源，并选择12—23点之间的可呈现起点");
        Instant now = automationClock.instant();
        repository.habit(new Habit(owner, "review", input.expectedRevision() + 1, input.availableHour(),
                input.source().strip(), "用户明确提供的候选作息，可能随当天安排变化；不表示睡眠状态", now,
                now.plus(Duration.ofDays(30)), input.active()), input.expectedRevision());
        return habit(owner);
    }
    /** 用户反馈是唯一反馈来源；刷新和未回复均不会调用此入口。 */
    @Transactional
    public void feedback(String owner, String id, Feedback input) {
        if (input == null || !Set.of("CANCEL", "HELPFUL", "NOT_RELEVANT", "LATER").contains(input.decision())
                || input.text() == null || input.text().length() > 500) throw new IllegalArgumentException("反馈无效");
        var c = repository.candidates(owner).stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("候选不存在"));
        if (input.decision().equals("CANCEL") && !c.state().equals("PENDING"))
            throw new IllegalArgumentException("已呈现内容不能撤回，可提交不相关反馈");
        repository.feedback(owner, c, input.decision().equals("CANCEL") ? "CANCELLED" : c.state(),
                input.decision() + ":" + input.text(), automationClock.instant());
    }
    /** 人工事件候选复用已生成内容；不允许拿另一个用户或未完成版本当来源。 */
    @Transactional
    public void propose(String owner, String contentId) {
        var s = authorization.require(owner);
        var content = repository.content(owner, contentId).orElseThrow(() -> new IllegalArgumentException("来源内容不存在"));
        if (!s.proactive() || !s.grantId().equals(content.grantId()) || !Set.of("READY", "DEGRADED").contains(content.state()))
            throw new IllegalArgumentException("请先授权主动呈现并完成复盘");
        Instant now = automationClock.instant();
        requireCapacity(owner, "review-" + content.day(), "daily");
        repository.candidate(new Candidate(UUID.randomUUID().toString(), owner, "review-" + content.day(), "daily",
                content.id(), content.id(), "PENDING", content.availableAt(), now.plus(Duration.ofHours(36)), now, null, ""));
    }
    /** 人工事件只引用当前授权版本的真实材料，恢复必须关联已经呈现的同主题事件。 */
    @Transactional
    public void event(String owner, EventCandidate input) {
        var s = authorization.require(owner);
        if (!s.proactive() || input == null || input.topic() == null || input.topic().isBlank()
                || input.topic().length() > 80 || input.topic().contains("\n") || input.topic().indexOf('\0') >= 0
                || !Set.of("EVENT", "RECOVERY").contains(input.eventKind() == null ? "" : input.eventKind()))
            throw new IllegalArgumentException("请填写有效主题、来源和事件类型");
        var c = repository.content(owner, input.contentId()).orElseThrow(() -> new IllegalArgumentException("来源复盘不存在"));
        if (!c.grantId().equals(s.grantId()) || c.settingsRevision() != s.revision()
                || !Set.of("READY", "DEGRADED").contains(c.state())) throw new IllegalArgumentException("来源不在当前授权范围内");
        String source = c.materials().stream().filter(m -> m.id().equals(input.evidenceId()))
                .map(m -> m.kind() + ":" + m.sourceId() + ":" + m.text()).findFirst().orElseGet(() ->
                    c.facts().stream().filter(f -> f.id().equals(input.evidenceId())).map(f ->
                        f.domain() + ":" + f.metricKey() + ":" + f.period() + ":" + f.currency() + ":" + f.value())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("事件必须引用该版本的实际证据")));
        String topic = input.topic().strip();
        if (input.eventKind().equals("RECOVERY") && repository.candidates(owner).stream().noneMatch(v ->
                v.topic().equals(topic) && v.eventKind().equals("EVENT") && v.state().equals("PRESENTED")))
            throw new IllegalArgumentException("尚无已呈现的同主题事件，不单独发送恢复消息");
        Instant now = automationClock.instant();
        String novelty = input.eventKind() + ":" + butvan.agent.network.review.service.ReviewPolicy.fingerprint(source);
        requireCapacity(owner, topic, novelty);
        repository.candidate(new Candidate(UUID.randomUUID().toString(), owner, topic,
                novelty,
                c.id() + ":" + input.evidenceId(), c.id(), "PENDING", c.availableAt(), now.plus(Duration.ofHours(36)),
                now, null, "", input.eventKind()));
    }

    /** 每次扫描最多一百候选；不调用模型，不因未回复创建新候选。 */
    public void tick(String owner) {
        var s = authorization.require(owner);
        if (!s.proactive()) return;
        Instant now = automationClock.instant();
        repository.expire(owner, now);
        var local = now.atZone(ZoneId.of(s.timezone()));
        if (local.getHour() < 12 || !local.toLocalTime().isBefore(LocalTime.of(23, 30))) return;
        for (var c : repository.candidates(owner)) {
            if (!c.state().equals("PENDING")) continue;
            if (!now.isBefore(c.expiresAt())) {
                repository.candidateState(owner, c.id(), "PENDING", "EXPIRED", null, ""); continue;
            }
            if (now.isBefore(due(c))) continue;
            // 冷启动：全域每日最多一次，同主题至少24小时；无新信息由唯一键阻止入队。
            if (repository.lastPresentation(owner).filter(sent -> now.isBefore(sent.plus(Duration.ofHours(24)))).isPresent()) continue;
            if (!repository.candidateState(owner, c.id(), "PENDING", "PRESENTED", now, "")) continue;
            if (s.feishu()) deliveries.send(owner, c.contentId(), false);
        }
    }
    private void requireCapacity(String owner, String topic, String novelty) {
        if (repository.pendingCount(owner) >= 100 && !repository.candidateExists(owner, topic, novelty))
            throw new IllegalArgumentException("主动候选积压已达一百条，请先取消或等待过期");
    }
    private Instant due(Candidate c) {
        var s = repository.settings(c.owner());
        Instant base = LifeReviewService.availableAt(c.dueAt(), s.timezone());
        return repository.habit(c.owner()).filter(h -> h.active() && automationClock.instant().isBefore(h.expiresAt()))
                .map(h -> {
                    var local = base.atZone(ZoneId.of(s.timezone()));
                    Instant preferred = local.toLocalDate().atTime(h.availableHour(), 0).atZone(local.getZone()).toInstant();
                    return preferred.isAfter(base) ? preferred : base;
                }).orElse(base);
    }
}
