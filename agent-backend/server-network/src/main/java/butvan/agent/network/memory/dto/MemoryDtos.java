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
                                   int retentionDays, int dailyWriteLimit, int dailyQueryLimit) { }
    public record SaveSettings(long expectedVersion, boolean autoSave, boolean autoRecall) { }
    public record Authorize(long expectedRevision, String recipientFingerprint, boolean allowSave, boolean allowRecall,
                            Set<Channel> channels, int retentionDays, int dailyWriteLimit, int dailyQueryLimit,
                            boolean confirmed, String requestId) { }
    public record Revoke(long expectedRevision, String requestId) { }
    public record AuthorizationResponse(long revision, Instant effectiveAt) { }
    /** 只读连通性与合约摘要，不把健康状态当成数据处理授权或删除保证。 */
    public record ConnectionTestResponse(String status, boolean configured, boolean secureTransport, boolean reachable,
                                         boolean ready, String apiVersion, boolean contractFieldsPresent,
                                         boolean dataOperationsReady, boolean credentialConfigured, String message) { }
    public record DocumentResponse(String id, long version, Instant occurredAt, String channel, String state,
                                   String syncState, String deletionState, boolean corrected, String operationId) { }
    public record DocumentPage(List<DocumentResponse> items, String nextCursor) { }
    public record DocumentDetail(DocumentResponse document, String originalStatus, String originalText,
                                 String correctedText, String correctionStatus) { }
    public record Correct(long expectedVersion, String correctedText, boolean confirmed, String requestId) {
        @Override public String toString() { return "Correct{content=REDACTED}"; }
    }
    public record DeleteDocument(long expectedVersion, boolean confirmed, String requestId) { }
    public record OperationResponse(String id, String sourceId, String kind, long version, String state,
                                    Instant updatedAt, boolean retryAllowed) { }
}
