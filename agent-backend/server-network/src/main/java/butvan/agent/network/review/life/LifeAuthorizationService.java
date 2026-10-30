package butvan.agent.network.review.life;

import butvan.agent.agents.review.PersonalDeliveryPort;
import butvan.agent.network.review.life.LifeReviewModels.Settings;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import butvan.agent.network.review.service.ReviewModelCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 独立 life-v1 用途授权；任何配置变更使原授权失效。 */
@Service
@RequiredArgsConstructor
public class LifeAuthorizationService {
    public static final String POLICY = "life-v1";
    private static final Set<String> SOURCES = Set.of("BUSINESS", "JOURNAL", "CHAT", "FINANCE", "GOALS");
    private final LifeReviewRepository repository;
    private final ReviewModelCatalog models;
    private final List<PersonalDeliveryPort> channels;
    private final Clock automationClock;

    /** 返回当前保存范围与有效授权状态，不进行模型或渠道网络调用。 */
    public SettingsView view(String owner) {
        var s = repository.settings(owner);
        var target = target();
        boolean authorized = s.enabled() && !s.grantId().isBlank();
        if (authorized) {
            try { verifyIdentity(s); }
            catch (IllegalArgumentException invalid) { authorized = false; }
        }
        return new SettingsView(s.revision(), s.enabled(), s.sources(), s.providerId(), s.modelName(), s.timezone(),
                s.historyDays(), s.dailyLimit(), s.proactive(), s.feishu(), authorized, POLICY,
                target.available(), target.label());
    }
    @Transactional
    public SettingsView save(String owner, SaveSettings input) {
        if (input == null || input.sources() == null || !SOURCES.containsAll(input.sources())
                || input.sources().isEmpty() || input.historyDays() < 1 || input.historyDays() > 7
                || input.dailyLimit() < 1 || input.dailyLimit() > 3)
            throw new IllegalArgumentException("请选择有效范围、1—7 日窗口和 1—3 次每日额度");
        var old = repository.settings(owner);
        if (old.revision() != input.expectedRevision()) throw new IllegalArgumentException("设置已变化，请刷新");
        ZoneId zone;
        try { zone = ZoneId.of(input.timezone()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("时区无效"); }
        var model = models.resolve(input.providerId(), input.modelName());
        var target = target();
        if (input.feishu() && !target.available()) throw new IllegalArgumentException("请先配置并连接仅限本人的飞书渠道");
        repository.settings(new Settings(owner, old.revision() + 1, input.enabled(), "", input.sources(),
                model.providerId(), model.modelName(), model.fingerprint(), zone.getId(), input.historyDays(),
                input.dailyLimit(), input.proactive(), input.feishu(), input.feishu() ? target.fingerprint() : "", null), old.revision());
        repository.revokeGrants(owner, automationClock.instant());
        repository.cancel(owner);
        return view(owner);
    }
    @Transactional
    public SettingsView grant(String owner, Consent consent) {
        var s = repository.settings(owner);
        if (consent == null || !consent.confirmed() || !POLICY.equals(consent.policyVersion())
                || consent.expectedRevision() != s.revision() || !s.enabled() || s.revision() == 0)
            throw new IllegalArgumentException("请先保存已启用的生活复盘设置，再明确确认当前范围与固定模型");
        verifyIdentity(s);
        var next = new Settings(owner, s.revision() + 1, s.enabled(), UUID.randomUUID().toString(), s.sources(),
                s.providerId(), s.modelName(), s.fingerprint(), s.timezone(), s.historyDays(), s.dailyLimit(),
                s.proactive(), s.feishu(), s.targetFingerprint(), automationClock.instant());
        repository.settings(next, s.revision());
        repository.grant(next);
        return view(owner);
    }
    @Transactional
    public SettingsView revoke(String owner, Consent consent) {
        var s = repository.settings(owner);
        if (consent == null || !consent.confirmed() || consent.expectedRevision() != s.revision())
            throw new IllegalArgumentException("请确认当前授权版本");
        repository.settings(new Settings(owner, s.revision() + 1, false, "", s.sources(), s.providerId(), s.modelName(),
                s.fingerprint(), s.timezone(), s.historyDays(), s.dailyLimit(), false, false, "", null), s.revision());
        repository.revokeGrants(owner, automationClock.instant());
        repository.cancel(owner);
        return view(owner);
    }
    /** Worker 在读取资料、发送与发布之前均复核，配置文件变化同样使操作失效。 */
    public Settings require(String owner) {
        var s = repository.settings(owner);
        if (!s.enabled() || s.grantId().isBlank()) throw new IllegalArgumentException("生活复盘尚未单独授权");
        verifyIdentity(s);
        return s;
    }
    private void verifyIdentity(Settings s) {
        if (!models.resolve(s.providerId(), s.modelName()).fingerprint().equals(s.fingerprint()))
            throw new IllegalArgumentException("模型接收方已变化，请重新授权");
        if (s.feishu() && !target().fingerprint().equals(s.targetFingerprint()))
            throw new IllegalArgumentException("飞书本人目标已变化或不可用，请检查连接");
    }
    /** 返回唯一渠道已核实的本人目标摘要；不接受客户端传入接收人。 */
    public PersonalDeliveryPort.Target target() {
        return channels.size() == 1 ? channels.getFirst().target()
                : new PersonalDeliveryPort.Target(false, "", "未连接本人飞书");
    }
}
