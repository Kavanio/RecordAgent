package butvan.agent.network.memory.dto;

/** 真实聊天试用的连接配置；凭据只进受保护文件，不回显、不作为用途授权。 */
public final class MemoryTrialDtos {
    private MemoryTrialDtos() { }
    public record Connection(long version, boolean configured, String endpoint, String processingChain,
                             boolean secureTransport, boolean credentialConfigured, String recipientFingerprint, String message) { }
    public record Connect(long expectedVersion, String endpoint, String token, boolean clearToken, String processingChain,
                          boolean acceptTrialRisks, boolean allowPlaintext, String requestId) {
        @Override public String toString() { return "MemoryTrialConnect{configuration=REDACTED}"; }
    }
}
