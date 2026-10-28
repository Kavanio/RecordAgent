package butvan.agent.network.memory.dto;

import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import java.time.Instant;
import java.util.Set;

/** 设置与授权 DTO；不接受 owner、bank、任意 URL、Token 或回填生效时间。 */
public final class MemoryDtos {
    private MemoryDtos() { }
    public record SettingsResponse(long version, boolean autoSave, boolean autoRecall, long authorizationRevision,
                                   boolean authorized, boolean remoteAvailable, String recipientFingerprint) { }
    public record SaveSettings(long expectedVersion, boolean autoSave, boolean autoRecall) { }
    public record Authorize(long expectedRevision, String recipientFingerprint, boolean allowSave, boolean allowRecall,
                            Set<Channel> channels, int retentionDays, int dailyWriteLimit, int dailyQueryLimit,
                            boolean confirmed, String requestId) { }
    public record Revoke(long expectedRevision, String requestId) { }
    public record AuthorizationResponse(long revision, Instant effectiveAt) { }
}
