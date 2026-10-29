package butvan.agent.network.memory.model;

import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import java.time.Instant;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;

/** 记忆模块内部值对象；Controller 必须另用 DTO，不透传持久化记录。 */
public final class MemoryModels {
    private MemoryModels() { }

    /** V1 授权把期限与自授权日额度写在同一份确认里；V2 改为显式召回模式与独立运行保护。 */
    public static final int POLICY_V1 = 1;
    public static final int POLICY_V2 = 2;
    /** 长期召回只表示不按统一天数排除来源，不代表永不失效或远端物理删除。 */
    public enum RecallMode { ALL_AUTHORIZED, RECENT_DAYS }
    /** 调用计量的四类来源：V1 的合并查询、V2 拆开的状态核对与真实召回。 */
    public enum Meter { WRITE, STATUS, LEGACY_QUERY, RECALL }

    public record Settings(String spaceId, long version, boolean autoSave, boolean autoRecall) { }
    public record State(Settings settings, Grant grant, String recipientFingerprint, boolean authorized) { }
    public record Grant(long revision, boolean active, String fingerprint, boolean save, boolean recall,
                        Set<Channel> channels, Instant effectiveAt, int retentionDays, int writeLimit, int queryLimit,
                        int policyVersion, RecallMode recallMode) {
        /** 只有明确选择 V2 长期模式才取消统一年龄上限。 */
        public boolean longTermRecall() { return policyVersion >= POLICY_V2 && recallMode == RecallMode.ALL_AUTHORIZED; }
    }
    /** 不含正文的运行保护；修改它不产生新授权，也不使已登记来源失效。 */
    public record RuntimePolicy(String spaceId, long version, int batchSize, int dailyWriteLimit,
                                int dailyStatusLimit, int dailyRecallLimit) { }
    /** 按授权版本解析本次调用使用哪类配额；V1 仍共用原 query_calls 计数。 */
    public record Limits(int writeLimit, int statusLimit, int recallLimit, boolean legacyQueryMeter) {
        public static Limits of(Grant grant, RuntimePolicy runtime) {
            return grant.policyVersion() >= POLICY_V2
                    ? new Limits(runtime.dailyWriteLimit(), runtime.dailyStatusLimit(), runtime.dailyRecallLimit(), false)
                    : new Limits(grant.writeLimit(), grant.queryLimit(), grant.queryLimit(), true);
        }
        /** 状态核对在 V2 使用独立计量，不挤占真实召回额度。 */
        public Meter statusMeter() { return legacyQueryMeter ? Meter.LEGACY_QUERY : Meter.STATUS; }
        /** 召回在 V1 继承合并查询计数，保持旧授权原有上限。 */
        public Meter recallMeter() { return legacyQueryMeter ? Meter.LEGACY_QUERY : Meter.RECALL; }
    }
    public record Authorization(long expectedRevision, String recipientFingerprint, boolean allowSave,
                                boolean allowRecall, Set<Channel> channels, int retentionDays,
                                int dailyWriteLimit, int dailyQueryLimit, int policyVersion, RecallMode recallMode,
                                boolean continuousProcessing, boolean acknowledgeSourceImpact,
                                boolean confirmed, String requestId) {
        public Authorization {
            channels = channels == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(channels));
            recallMode = recallMode == null ? RecallMode.RECENT_DAYS : recallMode;
            policyVersion = policyVersion == 0 ? POLICY_V1 : policyVersion;
        }
        /** V1 兼容入口：旧客户端只提交保留期限与自授权日额度。 */
        public Authorization(long expectedRevision, String recipientFingerprint, boolean allowSave, boolean allowRecall,
                             Set<Channel> channels, int retentionDays, int dailyWriteLimit, int dailyQueryLimit,
                             boolean confirmed, String requestId) {
            this(expectedRevision, recipientFingerprint, allowSave, allowRecall, channels, retentionDays,
                    dailyWriteLimit, dailyQueryLimit, POLICY_V1, RecallMode.RECENT_DAYS, false, false, confirmed, requestId);
        }
        public boolean v2() { return policyVersion == POLICY_V2; }
    }
    public record AuthorizationResult(long revision, Instant effectiveAt) { }
    public record Command(String kind, String payloadHash, long revision, Instant effectiveAt) { }
}
