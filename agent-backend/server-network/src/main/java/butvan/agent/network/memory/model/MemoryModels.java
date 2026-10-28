package butvan.agent.network.memory.model;

import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import java.time.Instant;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;

/** 记忆模块内部值对象；Controller 必须另用 DTO，不透传持久化记录。 */
public final class MemoryModels {
    private MemoryModels() { }
    public record Settings(String spaceId, long version, boolean autoSave, boolean autoRecall) { }
    public record State(Settings settings, Grant grant, String recipientFingerprint, boolean authorized) { }
    public record Grant(long revision, boolean active, String fingerprint, boolean save, boolean recall,
                        Set<Channel> channels, Instant effectiveAt, int retentionDays, int writeLimit, int queryLimit) { }
    public record Authorization(long expectedRevision, String recipientFingerprint, boolean allowSave,
                                boolean allowRecall, Set<Channel> channels, int retentionDays,
                                int dailyWriteLimit, int dailyQueryLimit, boolean confirmed, String requestId) {
        public Authorization {
            channels = channels == null ? Set.of() : Collections.unmodifiableSet(new HashSet<>(channels));
        }
    }
    public record AuthorizationResult(long revision, Instant effectiveAt) { }
    public record Command(String kind, String payloadHash, long revision, Instant effectiveAt) { }
}
