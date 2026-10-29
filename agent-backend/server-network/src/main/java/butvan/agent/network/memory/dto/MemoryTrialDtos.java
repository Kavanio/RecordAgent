package butvan.agent.network.memory.dto;

/** 真实聊天试用的连接配置；凭据只进受保护文件，不回显、不作为用途授权。 */
public final class MemoryTrialDtos {
    private MemoryTrialDtos() { }
    public record Connection(long version, boolean configured, String endpoint, String processingChain,
                             boolean secureTransport, boolean credentialConfigured, String recipientFingerprint, String message, String bankId) {
        public Connection(long version, boolean configured, String endpoint, String processingChain,
                          boolean secureTransport, boolean credentialConfigured, String recipientFingerprint, String message) {
            this(version, configured, endpoint, processingChain, secureTransport, credentialConfigured, recipientFingerprint, message, "");
        }
        public Connection withBankId(String value) {
            return new Connection(version, configured, endpoint, processingChain, secureTransport, credentialConfigured, recipientFingerprint, message, value);
        }
    }
    public record Connect(long expectedVersion, String endpoint, String token, boolean clearToken, String processingChain,
                          boolean acceptTrialRisks, boolean allowPlaintext, String requestId, String bankId) {
        public Connect(long expectedVersion, String endpoint, String token, boolean clearToken, String processingChain,
                       boolean acceptTrialRisks, boolean allowPlaintext, String requestId) {
            this(expectedVersion, endpoint, token, clearToken, processingChain, acceptTrialRisks, allowPlaintext, requestId, null);
        }
        public Connect withBankId(String value) {
            return new Connect(expectedVersion, endpoint, token, clearToken, processingChain, acceptTrialRisks, allowPlaintext, requestId, value);
        }
        @Override public String toString() { return "MemoryTrialConnect{configuration=REDACTED}"; }
    }
}
