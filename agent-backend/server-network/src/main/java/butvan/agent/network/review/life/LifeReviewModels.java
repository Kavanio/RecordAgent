package butvan.agent.network.review.life;

import butvan.agent.agents.review.ReviewDraft;
import java.time.*;
import java.util.*;

/** 生活复盘内部模型：文字来源与数值证据独立，版本发布后不可变。 */
public final class LifeReviewModels {
    private LifeReviewModels() {}
    /** 配置与授权绑定同一版本；空 grantId 表示未授权。 */
    public record Settings(String owner, int revision, boolean enabled, String grantId, Set<String> sources,
                           String providerId, String modelName, String fingerprint, String timezone,
                           int historyDays, int dailyLimit, boolean proactive, boolean feishu,
                           String targetFingerprint, Instant authorizedAt) {
        public Settings { sources = Set.copyOf(sources); }
        public static Settings defaults(String owner) {
            return new Settings(owner, 0, false, "", Set.of(), "", "", "", "Asia/Shanghai", 7, 1,
                    false, false, "", null);
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
}
