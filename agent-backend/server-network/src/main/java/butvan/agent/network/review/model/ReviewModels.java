package butvan.agent.network.review.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** 复盘内部领域记录：不含模型密钥、原始备注或聊天正文。 */
public final class ReviewModels {
    private ReviewModels() {}

    /** 固定模型接收方身份；指纹由服务端从已有配置计算。 */
    public record ModelIdentity(String providerId, String modelName, String protocol, String fingerprint) {}

    /** 版本化用户配置；保存配置不等于授予权限。 */
    public record Settings(String owner, boolean enabled, Set<String> periods, Set<String> domains,
                           int historyDays, int dailyLimit, String providerId, String modelName,
                           String fingerprint, int revision, Instant updatedAt) {
        public Settings { periods = Set.copyOf(periods); domains = Set.copyOf(domains); }
        public static Settings defaults(String owner) {
            return new Settings(owner, false, Set.of("DAILY_REPORT", "WEEKLY_REPORT", "MONTHLY_REPORT"),
                    Set.of("FINANCE", "TODO", "STUDY"), 180, 3, "", "", "", 0, Instant.EPOCH);
        }
    }

    /** 独立授权记录；范围缩小可继续使用，扩大后须重授。 */
    public record Grant(String id, String owner, String policyVersion, Set<String> periods, Set<String> domains,
                        int historyDays, int dailyLimit, String fingerprint, String state, int revision,
                        Instant createdAt, Instant revokedAt) {
        public Grant { periods = Set.copyOf(periods); domains = Set.copyOf(domains); }
    }

    /** 程序计算的事实，金额分币种，以最小单位整数保存。 */
    public record Fact(String domain, String metricKey, String subjectId, String currency, String unit, long value) {}
    /** 无记录与读取失败单独表示。 */
    public record SourceStatus(String domain, String state) {}
    /** 已结束自然日的不可变采集快照；不是原始账本的不可变历史。 */
    public record FactSnapshot(String id, String owner, String grantId, LocalDate from, LocalDate to,
                               String timezone, Instant sourceAsOf, int metricVersion, String fingerprint,
                               Instant expiresAt, List<Fact> facts, List<SourceStatus> sources) {
        public FactSnapshot { facts = List.copyOf(facts); sources = List.copyOf(sources); }
    }
    /** 历史列表仅返回轻量元数据，避免分页时逐条加载事实明细。 */
    public record SnapshotHeader(String id, LocalDate from, LocalDate to, String timezone, Instant sourceAsOf,
                                 int metricVersion, String fingerprint, Instant expiresAt) {}
    /** 仅由用户维护的目标，不能由模型推测替代。 */
    public record Goal(String id, String owner, String title, String constraintText, String successCriterion,
                       String state, int revision, Instant createdAt, Instant updatedAt) {}
}
