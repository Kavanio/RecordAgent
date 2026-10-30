package butvan.agent.agents.review;

/** 本人渠道投递端口：调用方持久化副本和意图，渠道只执行一次发送。 */
public interface PersonalDeliveryPort {
    /** 身份只取渠道已核实的单一本人配置，不接受请求体指定目标。 */
    Target target();
    Attempt send(String expectedFingerprint, String deliveryId, String text);
    record Target(boolean available, String fingerprint, String label) {}
    record Attempt(String state, String messageId) {}
}
