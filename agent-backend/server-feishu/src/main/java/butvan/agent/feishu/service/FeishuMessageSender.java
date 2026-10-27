package butvan.agent.feishu.service;

/** 消息发送 seam：一次调用只对应一个平台请求，重试由投递模块统一负责。 */
@FunctionalInterface
public interface FeishuMessageSender {
    /** 使用指定幂等标识发送一条消息，返回确定成功、可重试、失败或未知送达。 */
    Attempt send(String chatId, String replyTo, String deliveryId, String body, Format format);

    /** 回复正文按 Markdown 渲染；回执、进度与控制反馈保持普通文本，不制造卡片噪音。 */
    enum Format { TEXT, MARKDOWN }

    /** UNKNOWN 表示可能已送达，绝不能自动换标识重试。 */
    enum Status { SENT, RETRYABLE, FAILED, UNKNOWN }

    /** 仅携带脱敏机器码，不透出平台原始错误响应或异常正文。 */
    record Attempt(Status status, String messageId, String code, long retryAfterMillis) {
        public Attempt(Status status, String messageId, String code) { this(status, messageId, code, 0); }
        public static Attempt sent(String id) { return new Attempt(Status.SENT, id, "0"); }
        public static Attempt failed(Status status, String code) { return new Attempt(status, null, code); }
    }
}
