package butvan.agent.network.review.life;

import butvan.agent.agents.review.ReviewDraft;
import java.time.*;
import java.util.*;

/** 生活复盘内部模型：文字来源与数值证据独立，版本发布后不可变。 */
public final class LifeReviewModels {
    private LifeReviewModels() {}
    /** 配置与授权绑定同一版本；空 grantId 表示未授权；idleCheckinDays=0 表示不做久未互动问候。 */
    public record Settings(String owner, int revision, boolean enabled, String grantId, Set<String> sources,
                           String providerId, String modelName, String fingerprint, String timezone,
                           int historyDays, int dailyLimit, boolean proactive, boolean feishu,
                           String targetFingerprint, Instant authorizedAt, int idleCheckinDays,
                           boolean weatherAlertEnabled, boolean expenseAlertEnabled,
                           boolean serverAlertEnabled, String serverProbeUrl, String serverProbeName,
                           boolean mailAlertEnabled, String imapHost, int imapPort,
                           String imapUsername, String imapPassword,
                           String mailSenderWhitelist, String mailSubjectKeywords) {
        public Settings {
            sources = Set.copyOf(sources);
            serverProbeUrl = serverProbeUrl == null ? "" : serverProbeUrl.strip();
            serverProbeName = serverProbeName == null || serverProbeName.isBlank() ? "应用服务器" : serverProbeName.strip();
            imapHost = imapHost == null ? "" : imapHost.strip();
            imapPort = imapPort <= 0 ? 993 : imapPort;
            imapUsername = imapUsername == null ? "" : imapUsername.strip();
            imapPassword = imapPassword == null ? "" : imapPassword;
            mailSenderWhitelist = mailSenderWhitelist == null ? "" : mailSenderWhitelist.strip();
            mailSubjectKeywords = mailSubjectKeywords == null ? "" : mailSubjectKeywords.strip();
        }
        public Settings(String owner, int revision, boolean enabled, String grantId, Set<String> sources,
                String providerId, String modelName, String fingerprint, String timezone,
                int historyDays, int dailyLimit, boolean proactive, boolean feishu,
                String targetFingerprint, Instant authorizedAt, int idleCheckinDays,
                boolean weatherAlertEnabled, boolean expenseAlertEnabled,
                boolean serverAlertEnabled, String serverProbeUrl, String serverProbeName) {
            this(owner, revision, enabled, grantId, sources, providerId, modelName, fingerprint, timezone,
                    historyDays, dailyLimit, proactive, feishu, targetFingerprint, authorizedAt, idleCheckinDays,
                    weatherAlertEnabled, expenseAlertEnabled, serverAlertEnabled, serverProbeUrl, serverProbeName,
                    false, "", 993, "", "", "", "");
        }
        public Settings(String owner, int revision, boolean enabled, String grantId, Set<String> sources,
                String providerId, String modelName, String fingerprint, String timezone,
                int historyDays, int dailyLimit, boolean proactive, boolean feishu,
                String targetFingerprint, Instant authorizedAt, int idleCheckinDays,
                boolean weatherAlertEnabled, boolean expenseAlertEnabled) {
            this(owner, revision, enabled, grantId, sources, providerId, modelName, fingerprint, timezone,
                    historyDays, dailyLimit, proactive, feishu, targetFingerprint, authorizedAt, idleCheckinDays,
                    weatherAlertEnabled, expenseAlertEnabled, false, "", "应用服务器",
                    false, "", 993, "", "", "", "");
        }
        public Settings(String owner, int revision, boolean enabled, String grantId, Set<String> sources,
                String providerId, String modelName, String fingerprint, String timezone,
                int historyDays, int dailyLimit, boolean proactive, boolean feishu,
                String targetFingerprint, Instant authorizedAt, int idleCheckinDays) {
            this(owner, revision, enabled, grantId, sources, providerId, modelName, fingerprint, timezone,
                    historyDays, dailyLimit, proactive, feishu, targetFingerprint, authorizedAt, idleCheckinDays,
                    false, false, false, "", "应用服务器",
                    false, "", 993, "", "", "", "");
        }
        public Settings(String owner, int revision, boolean enabled, String grantId, Set<String> sources,
                String providerId, String modelName, String fingerprint, String timezone,
                int historyDays, int dailyLimit, boolean proactive, boolean feishu,
                String targetFingerprint, Instant authorizedAt) {
            this(owner, revision, enabled, grantId, sources, providerId, modelName, fingerprint, timezone,
                    historyDays, dailyLimit, proactive, feishu, targetFingerprint, authorizedAt, 0, false, false,
                    false, "", "应用服务器",
                    false, "", 993, "", "", "", "");
        }
        public static Settings defaults(String owner) {
            return new Settings(owner, 0, false, "", Set.of(), "", "", "", "Asia/Shanghai", 7, 1,
                    false, false, "", null, 0, false, false, false, "", "应用服务器",
                    false, "", 993, "", "", "", "");
        }
        /** 久未互动问候需要主动呈现、本人飞书与问候天数同时开启。 */
        public boolean idleCheckinEnabled() { return proactive && feishu && idleCheckinDays > 0; }
        /** 天气变化提醒需要主动呈现、本人飞书与天气提醒开关同时开启。 */
        public boolean weatherSignalEnabled() { return proactive && feishu && weatherAlertEnabled; }
        /** 支出变化提醒需要主动呈现、本人飞书、财务来源授权与支出提醒开关同时开启。 */
        public boolean expenseSignalEnabled() {
            return proactive && feishu && expenseAlertEnabled && sources.contains("FINANCE");
        }
        /** 服务器健康探针提醒需要主动呈现、本人飞书、开关开启且探针地址有效。 */
        public boolean serverSignalEnabled() {
            return proactive && feishu && serverAlertEnabled && !serverProbeUrl.isBlank();
        }
        /** 重要邮件提醒需要主动呈现、本人飞书、开关开启且 IMAP 连接关键凭据已配置。 */
        public boolean mailSignalEnabled() {
            return proactive && feishu && mailAlertEnabled
                    && !imapHost.isBlank() && !imapUsername.isBlank() && !imapPassword.isBlank();
        }
    }
    /** 保留原始角色、时间及截断状态；不将模型推断保存为原始记录。 */
    public record Material(String id, String kind, String sourceId, String occurredAt, String text,
                           boolean truncated) {}
    /** 截止点采用真实采集时间；旧版本不会因补录被覆盖。 */
    public record Content(String id, String owner, LocalDate day, String timezone, int version, String grantId,
                          int settingsRevision, String state, Instant sourceAsOf, Instant availableAt,
                          Instant createdAt, long generation, List<Material> materials,
                          List<butvan.agent.agents.review.ReviewGenerationRequest.Evidence> facts,
                          String coverage, ReviewDraft draft, List<String> questions, String message) {
        public Content { materials = List.copyOf(materials); facts = List.copyOf(facts); questions = List.copyOf(questions); }
        public Content finish(String nextState, ReviewDraft nextDraft, String note) {
            return new Content(id, owner, day, timezone, version, grantId, settingsRevision, nextState, sourceAsOf,
                    availableAt, createdAt, generation, materials, facts, coverage, nextDraft, questions, note);
        }
    }
    /** 每渠道独立结果；UNKNOWN 不代表失败。 */
    public record Delivery(String id, String owner, String contentId, String channel, String state,
                           String targetFingerprint, String messageId, int attempts, Instant updatedAt) {}
    /** 候选通过主题与新颖性唯一键去重，未回复不修改状态。 */
    public record Candidate(String id, String owner, String topic, String novelty, String sourceId,
                            String contentId, String state, Instant dueAt, Instant expiresAt,
                            Instant createdAt, Instant sentAt, String feedback, String eventKind) {
        public Candidate(String id, String owner, String topic, String novelty, String sourceId, String contentId,
                String state, Instant dueAt, Instant expiresAt, Instant createdAt, Instant sentAt, String feedback) {
            this(id, owner, topic, novelty, sourceId, contentId, state, dueAt, expiresAt, createdAt, sentAt, feedback, "REVIEW");
        }
    }
    /** 作息是带来源、有效期与不确定性的候选，不是稳定画像。 */
    public record Habit(String owner, String topic, int revision, int availableHour, String source,
                        String uncertainty, Instant observedAt, Instant expiresAt, boolean active) {}
    /** 与复盘内容解耦的主动短消息；文案固定或由确定性程序组装，不调用模型。 */
    public record Nudge(String id, String owner, String kind, String dedupKey, String text, String state,
                        String targetFingerprint, String messageId, Instant createdAt, Instant sentAt,
                        String feedback) {}
    /** 现实信息源观测基线与当前活动事件标识，供变化对比与恢复通知判定。 */
    public record SignalState(String owner, String signalKey, String baselineValue, String activeEventKey,
                              Instant observedAt) {}
}
