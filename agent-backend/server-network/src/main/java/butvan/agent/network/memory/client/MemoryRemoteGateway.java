package butvan.agent.network.memory.client;

/** 远端删除内部 seam；HTTP 仅清理已知目标，完整证据不足时保留 UNKNOWN。 */
public interface MemoryRemoteGateway {
    /** 已核实接收方及处理链的指纹；未接入返回空，不能凭客户端字符串授权。 */
    String recipientFingerprint();

    /** 删除并核对派生结果及 operationId 指向的异步写入已终结；只有 VERIFIED 表示确认完成，超时保持 UNKNOWN。 */
    DeletionResult deleteAndVerify(Target target);

    record Target(String spaceId, String documentId, String recipientFingerprint, String operationId) { }
    enum DeletionResult { VERIFIED, FAILED, UNKNOWN }

    static MemoryRemoteGateway disabled() {
        return new MemoryRemoteGateway() {
            public String recipientFingerprint() { return ""; }
            public DeletionResult deleteAndVerify(Target target) { return DeletionResult.UNKNOWN; }
        };
    }
}
