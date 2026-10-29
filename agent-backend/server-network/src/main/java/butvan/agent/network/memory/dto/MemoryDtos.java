package butvan.agent.network.memory.dto;

import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import java.time.Instant;
import java.util.Set;
import java.util.List;

/** 设置与授权 DTO；不接受 owner、bank、任意 URL、Token 或回填生效时间。 */
public final class MemoryDtos {
    private MemoryDtos() { }
    public record SettingsResponse(long version, boolean autoSave, boolean autoRecall, long authorizationRevision,
                                   boolean authorized, boolean remoteAvailable, String recipientFingerprint,
                                   boolean allowSave, boolean allowRecall, Set<Channel> channels, Instant effectiveAt,
                                   int retentionDays, int dailyWriteLimit, int dailyQueryLimit,
                                   int policyVersion, String recallMode, RuntimePolicyResponse runtimePolicy) { }
    /** 运行保护与用途授权分开展示；它不改变授权 revision 或召回范围。 */
    public record RuntimePolicyResponse(long version, int batchSize, int dailyWriteLimit, int dailyStatusLimit,
                                        int dailyRecallLimit) { }
    public record SaveRuntimePolicy(long expectedVersion, int batchSize, int dailyWriteLimit, int dailyStatusLimit,
                                    int dailyRecallLimit) { }
    public record SaveSettings(long expectedVersion, boolean autoSave, boolean autoRecall) { }
    /** V2 字段均可为 null：V1 客户端只提交期限与日额度，后端按 V1 严格解释。 */
    public record Authorize(long expectedRevision, String recipientFingerprint, boolean allowSave, boolean allowRecall,
                            Set<Channel> channels, Integer retentionDays, Integer dailyWriteLimit, Integer dailyQueryLimit,
                            Integer policyVersion, String recallMode, Boolean continuousProcessing,
                            Boolean acknowledgeSourceImpact, boolean confirmed, String requestId) { }
    /** 当日调用计数与当前生效上限；V1 只有合并查询列，不与 V2 分类互相换算。 */
    public record UsageResponse(String day, int writeCalls, int legacyCombinedQueries, int statusCalls, int recallCalls,
                                int writeLimit, int statusLimit, int recallLimit) { }
    /** 只读的本地权威状态；不包含正文，也不代表远端完整删除或已形成具体事实。 */
    public record StatusResponse(long sourceTotal, long sourceActive, java.util.Map<String, Long> operations,
                                 Instant oldestPendingAt, java.util.List<String> pauseReasons, UsageResponse usage,
                                 java.util.Map<String, Long> skippedToday) { }
    public record Revoke(long expectedRevision, String requestId) { }
    public record AuthorizationResponse(long revision, Instant effectiveAt) { }
    /** 只读连通性与合约摘要，不把健康状态当成数据处理授权或删除保证。 */
    public record ConnectionTestResponse(String status, boolean configured, boolean secureTransport, boolean reachable,
                                         boolean ready, String apiVersion, boolean contractFieldsPresent,
                                         boolean dataOperationsReady, boolean credentialConfigured, String message) { }
    /** 上次已记录的已知目标清理步骤；不是完整擦除证明，不改变删除任务状态。 */
    public record CleanupProgress(boolean documentDeletionRecorded, boolean factsClearanceRecorded, int knownChildTasks,
                                  int childDeletionsRecorded, boolean parentDeletionRecorded) { }
    public record DocumentResponse(String id, long version, Instant occurredAt, String channel, String state,
                                   String syncState, String deletionState, boolean corrected, String operationId,
                                   CleanupProgress cleanupProgress) { }
    public record DocumentPage(List<DocumentResponse> items, String nextCursor) { }
    public record DocumentDetail(DocumentResponse document, String originalStatus, String originalText,
                                 String correctedText, String correctionStatus) { }
    public record Correct(long expectedVersion, String correctedText, boolean confirmed, String requestId) {
        @Override public String toString() { return "Correct{content=REDACTED}"; }
    }
    public record DeleteDocument(long expectedVersion, boolean confirmed, String requestId) { }
    public record OperationResponse(String id, String sourceId, String kind, long version, String state,
                                    Instant updatedAt, boolean retryAllowed, CleanupProgress cleanupProgress) { }
}
