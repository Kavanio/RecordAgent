package butvan.agent.network.review.service;

import butvan.agent.network.review.dto.ReviewDtos.*;
import butvan.agent.network.review.model.ReviewModels.*;
import butvan.agent.network.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.UUID;

/** 复盘配置与独立授权入口；当前阶段只提供本地基础能力，不触发模型或自动任务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewAuthorizationService {
    private final ReviewRepository repository;
    private final ReviewModelCatalog models;
    private final Clock automationClock;

    /** 后台读取权限凭据，包含当前配置与当前授权，不能复用到聊天。 */
    public record Authorization(Settings settings, Grant grant) {}

    @Transactional(readOnly = true)
    public SettingsView settings(String owner) {
        ReviewPolicy.requireOwner(owner);
        return view(repository.settings(owner));
    }

    /** 配置变更使用乐观锁，首次读取默认值不会写数据库。 */
    @Transactional
    public SettingsView save(String owner, SaveSettings input) {
        ReviewPolicy.requireOwner(owner);
        if (input == null || input.enabled() == null || input.expectedRevision() < 0
                || input.historyDays() < 1 || input.historyDays() > 180 || input.dailyLimit() < 1 || input.dailyLimit() > 3)
            throw new IllegalArgumentException("复盘配置无效，历史窗口为 1–180 天，每日上限为 1–3 次");
        var periods = ReviewPolicy.scope(input.periods(), ReviewPolicy.PERIODS);
        var domains = ReviewPolicy.scope(input.domains(), ReviewPolicy.DOMAINS);
        Settings old = repository.settings(owner);
        String provider = input.providerId() == null ? "" : input.providerId().strip();
        String name = input.modelName() == null ? "" : input.modelName().strip();
        String fingerprint;
        if (!input.enabled() && provider.equals(old.providerId()) && name.equals(old.modelName())) {
            // 模型已删除或连接不可用时仍必须允许用户暂停。
            fingerprint = old.fingerprint();
        } else fingerprint = models.resolve(provider, name).fingerprint();
        Settings value = new Settings(owner, input.enabled(), periods, domains, input.historyDays(), input.dailyLimit(),
                provider, name, fingerprint, input.expectedRevision() + 1, automationClock.instant());
        repository.saveSettings(value, input.expectedRevision());
        return view(value);
    }

    /** 仅为明确确认的当前设置授予权限；策略版本或配置变更时拒绝过期确认。 */
    @Transactional
    public GrantView grant(String owner, GrantRequest input) {
        ReviewPolicy.requireOwner(owner);
        if (input == null || !Boolean.TRUE.equals(input.confirmed()) || !ReviewPolicy.VERSION.equals(input.policyVersion()))
            throw new IllegalArgumentException("请阅读并明确确认当前自动复盘授权范围");
        Settings settings = repository.settings(owner);
        if (settings.revision() == 0 || settings.revision() != input.expectedSettingsRevision())
            throw new IllegalArgumentException("复盘配置已变化，请刷新后重新确认授权");
        var model = models.resolve(settings.providerId(), settings.modelName());
        if (!settings.fingerprint().equals(model.fingerprint()))
            throw new IllegalArgumentException("模型接收方已变化，请先保存配置再重新授权");
        var grant = new Grant(UUID.randomUUID().toString(), owner, ReviewPolicy.VERSION, settings.periods(), settings.domains(),
                settings.historyDays(), settings.dailyLimit(), settings.fingerprint(), "ACTIVE", 1, automationClock.instant(), null);
        repository.insertGrant(grant);
        return GrantView.from(grant);
    }

    /** 撤销不删除历史，也不隐式重新授予；后续任何采集都必须重新通过校验。 */
    @Transactional
    public GrantView revoke(String owner, String id, RevokeRequest input) {
        ReviewPolicy.requireOwner(owner);
        if (input == null || input.expectedRevision() < 1) throw new IllegalArgumentException("授权版本无效");
        repository.revoke(owner, id, input.expectedRevision(), automationClock.instant());
        return GrantView.from(repository.grant(owner, id).orElseThrow(() -> new IllegalArgumentException("授权不存在")));
    }

    /** 采集开始及落盘前均需调用，防止采集期间撤销后仍写入。 */
    @Transactional(readOnly = true)
    public Authorization requireEnabled(String owner) {
        ReviewPolicy.requireOwner(owner);
        Settings settings = repository.settings(owner);
        Grant grant = repository.activeGrant(owner).orElse(null);
        if (!settings.enabled() || !ReviewPolicy.covers(grant, settings, currentFingerprint(settings)))
            throw new IllegalArgumentException("自动复盘未开启、授权失效或模型接收方已变化");
        return new Authorization(settings, grant);
    }

    private SettingsView view(Settings settings) {
        Grant grant = repository.activeGrant(settings.owner()).orElse(null);
        boolean authorized = ReviewPolicy.covers(grant, settings, currentFingerprint(settings));
        return new SettingsView(settings.enabled(), authorized, false, "FOUNDATION_ONLY", ReviewPolicy.VERSION,
                settings.periods(), settings.domains(), settings.historyDays(), settings.dailyLimit(), settings.providerId(),
                settings.modelName(), settings.fingerprint(), settings.revision(), grant == null ? null : GrantView.from(grant));
    }

    private String currentFingerprint(Settings settings) {
        if (settings.providerId().isBlank() || settings.modelName().isBlank()) return "";
        try { return models.resolve(settings.providerId(), settings.modelName()).fingerprint(); }
        catch (IllegalArgumentException exception) {
            log.warn("复盘模型身份暂不可用，授权按无效处理");
            return "";
        }
    }
}
