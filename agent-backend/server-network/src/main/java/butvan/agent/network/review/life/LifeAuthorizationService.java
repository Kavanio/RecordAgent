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
    private static final Set<Integer> IDLE_DAYS = Set.of(0, 2, 3, 5, 7);
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
        boolean imapConfigured = s.imapPassword() != null && !s.imapPassword().isBlank();
        return new SettingsView(s.revision(), s.enabled(), s.sources(), s.providerId(), s.modelName(), s.timezone(),
                s.historyDays(), s.dailyLimit(), s.proactive(), s.feishu(), authorized, POLICY,
                target.available(), target.label(), s.idleCheckinDays(),
                s.weatherAlertEnabled(), s.expenseAlertEnabled(),
                s.serverAlertEnabled(), s.serverProbeUrl(), s.serverProbeName(),
                s.mailAlertEnabled(), s.imapHost(), s.imapPort(), s.imapUsername(), imapConfigured,
                s.mailSenderWhitelist(), s.mailSubjectKeywords(), mailStatus(s));
    }
    /** 只读本地最近一次检查结果，绝不在设置查询时连接邮箱。 */
    private String mailStatus(Settings s) {
        if (!s.mailAlertEnabled()) return "";
        if (s.imapPassword().isBlank()) return "请填写邮箱授权码";
        if (s.grantId().isBlank()) return "尚未授权，未开始检查邮箱";
        String source = butvan.agent.network.review.service.ReviewPolicy.fingerprint(LifeMailCredentials.identity(s));
        return repository.signalState(s.owner(), "MAIL").filter(state -> source.equals(state.sourceFingerprint()))
                .map(state -> state.baselineValue().startsWith("FAIL|")
                        ? "上次邮箱检查失败，请检查连接、加密设置或授权码" : "上次邮箱检查正常")
                .orElse("等待首次检查邮箱");
    }
    @Transactional
    public SettingsView save(String owner, SaveSettings input) {
        if (input == null || input.sources() == null || !SOURCES.containsAll(input.sources())
                || input.sources().isEmpty() || input.historyDays() < 1 || input.historyDays() > 7
                || input.dailyLimit() < 1 || input.dailyLimit() > 3)
            throw new IllegalArgumentException("请选择有效范围、1—7 日窗口和 1—3 次每日额度");
        if (!IDLE_DAYS.contains(input.idleCheckinDays()))
            throw new IllegalArgumentException("久未互动问候只能选择关闭或 2、3、5、7 天");
        if (input.idleCheckinDays() > 0 && !(input.proactive() && input.feishu()))
            throw new IllegalArgumentException("久未互动问候需要同时开启主动呈现和本人飞书投递");
        if ((input.weatherAlertEnabled() || input.expenseAlertEnabled() || input.serverAlertEnabled() || input.mailAlertEnabled()) && !(input.proactive() && input.feishu()))
            throw new IllegalArgumentException("天气、支出、服务器状态与重要邮件提醒需要同时开启主动呈现和本人飞书投递");
        if (input.expenseAlertEnabled() && !input.sources().contains("FINANCE"))
            throw new IllegalArgumentException("开启支出变化提醒需要先勾选财务数值来源");
        if (input.serverAlertEnabled()) {
            if (input.serverProbeUrl() == null || input.serverProbeUrl().isBlank())
                throw new IllegalArgumentException("开启服务器状态提醒必须填写有效的健康探针地址");
            validateProbeUrl(input.serverProbeUrl());
        }
        var old = repository.settings(owner);
        if (old.revision() != input.expectedRevision()) throw new IllegalArgumentException("设置已变化，请刷新");
        if (input.mailAlertEnabled()) {
            if (input.imapHost() == null || !input.imapHost().matches("[a-zA-Z0-9.-]{1,253}"))
                throw new IllegalArgumentException("IMAP 主机名格式不合法");
            if (input.imapPort() < 1 || input.imapPort() > 65535)
                throw new IllegalArgumentException("IMAP 端口必须在 1-65535 范围内");
            if (input.imapUsername() == null || input.imapUsername().isBlank() || input.imapUsername().length() > 128)
                throw new IllegalArgumentException("IMAP 账号必须在 1-128 字符内");
            boolean sameMailbox = old.imapHost().equalsIgnoreCase(input.imapHost().strip())
                    && old.imapPort() == input.imapPort() && old.imapUsername().equals(input.imapUsername().strip());
            boolean hasOldPass = sameMailbox && !old.imapPassword().isBlank();
            boolean hasNewPass = input.imapPassword() != null && !input.imapPassword().isBlank();
            if (!hasOldPass && !hasNewPass)
                throw new IllegalArgumentException("开启或更换邮箱连接必须填写 IMAP 授权码或密码");
        }
        ZoneId zone;
        try { zone = ZoneId.of(input.timezone()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("时区无效"); }
        var model = models.resolve(input.providerId(), input.modelName());
        var target = target();
        if (input.feishu() && !target.available()) throw new IllegalArgumentException("请先配置并连接仅限本人的飞书渠道");
        String imapPassword = (input.imapPassword() != null && !input.imapPassword().isBlank())
                ? input.imapPassword()
                : old.imapHost().equalsIgnoreCase(Objects.toString(input.imapHost(), "").strip())
                    && old.imapPort() == input.imapPort() && old.imapUsername().equals(Objects.toString(input.imapUsername(), "").strip())
                        ? old.imapPassword() : "";
        repository.settings(new Settings(owner, old.revision() + 1, input.enabled(), "", input.sources(),
                model.providerId(), model.modelName(), model.fingerprint(), zone.getId(), input.historyDays(),
                input.dailyLimit(), input.proactive(), input.feishu(), input.feishu() ? target.fingerprint() : "", null,
                input.idleCheckinDays(), input.weatherAlertEnabled(), input.expenseAlertEnabled(),
                input.serverAlertEnabled(), input.serverProbeUrl(), input.serverProbeName(),
                input.mailAlertEnabled(), input.imapHost(), input.imapPort(), input.imapUsername(), imapPassword,
                input.mailSenderWhitelist(), input.mailSubjectKeywords()), old.revision());
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
                s.proactive(), s.feishu(), s.targetFingerprint(), automationClock.instant(), s.idleCheckinDays(),
                s.weatherAlertEnabled(), s.expenseAlertEnabled(),
                s.serverAlertEnabled(), s.serverProbeUrl(), s.serverProbeName(),
                s.mailAlertEnabled(), s.imapHost(), s.imapPort(), s.imapUsername(), s.imapPassword(),
                s.mailSenderWhitelist(), s.mailSubjectKeywords());
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
                s.fingerprint(), s.timezone(), s.historyDays(), s.dailyLimit(), false, false, "", null, 0,
                false, false, false, "", "应用服务器",
                false, "", 993, "", "", "", ""), s.revision());
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
    static void validateProbeUrl(String url) {
        if (url == null || url.isBlank() || url.length() > 300)
            throw new IllegalArgumentException("服务器探针地址长度必须在 1—300 字符内");
        if (url.contains("\n") || url.indexOf('\0') >= 0 || url.contains(" "))
            throw new IllegalArgumentException("服务器探针地址不能包含空白或空字符");
        try {
            var uri = java.net.URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme))
                throw new IllegalArgumentException("服务器探针地址仅支持 http 或 https 协议");
            if (uri.getUserInfo() != null)
                throw new IllegalArgumentException("服务器探针地址不能携带用户身份凭据");
            if (uri.getHost() == null || uri.getHost().isBlank())
                throw new IllegalArgumentException("服务器探针地址的主机名无效");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("服务器探针地址格式无效");
        }
    }
}
