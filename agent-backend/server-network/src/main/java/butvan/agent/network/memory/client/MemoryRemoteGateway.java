package butvan.agent.network.memory.client;

/** 远端删除的内部 seam；M2-1 无 HTTP 实现，测试以假 Adapter 替换。 */
public interface MemoryRemoteGateway {
    /** 已核实接收方及处理链的指纹；未接入返回空，不能凭客户端字符串授权。 */
    String recipientFingerprint();

    /** 删除并核对派生结果；只有 VERIFIED 表示确认完成，超时保持 UNKNOWN。 */
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
