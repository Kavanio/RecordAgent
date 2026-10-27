package butvan.agent.feishu.service;

import com.lark.oapi.Client;
import com.lark.oapi.core.response.BaseResponse;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.im.v1.model.*;

import java.util.List;
import java.util.Map;

/** 直接使用 SDK 的单次 HTTP 接口；避免渠道便捷接口在重试时生成新的 uuid。 */
public final class LarkMessageSender implements FeishuMessageSender {
    private final Client client;

    public LarkMessageSender(Client client) { this.client = client; }

    @Override
    public Attempt send(String chatId, String replyTo, String deliveryId, String body, Format format) {
        try {
            boolean markdown = format == Format.MARKDOWN;
            String msgType = markdown ? "interactive" : "text";
            String content = Jsons.DEFAULT.toJson(markdown ? markdownCard(body) : Map.of("text", body));
            if (replyTo != null && !replyTo.isBlank()) {
                ReplyMessageResp response = client.im().message().reply(ReplyMessageReq.newBuilder()
                        .messageId(replyTo)
                        .replyMessageReqBody(ReplyMessageReqBody.newBuilder().msgType(msgType)
                                .content(content).uuid(deliveryId).build()).build());
                return classify(response, response != null && response.getData() != null
                        ? response.getData().getMessageId() : null);
            }
            CreateMessageResp response = client.im().message().create(CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder().receiveId(chatId).msgType(msgType)
                            .content(content).uuid(deliveryId).build()).build());
            return classify(response, response != null && response.getData() != null
                    ? response.getData().getMessageId() : null);
        } catch (Exception exception) {
            // 网络异常无法证明平台未受理；不记录可能包含密钥、正文和 URL 的异常文本。
            return Attempt.failed(Status.UNKNOWN, exception.getClass().getSimpleName());
        }
    }

    /**
     * 卡片 JSON 2.0 的富文本组件才支持完整 Markdown（标题、引用、行内代码、表格与代码块）；
     * 1.0 只支持子集，正文会被当成普通文本渲染。卡片不使用头部，避免每条回复多出一个标题。
     */
    static Map<String, Object> markdownCard(String markdown) {
        return Map.of("schema", "2.0",
                "config", Map.of("update_multi", true),
                "body", Map.of("elements", List.of(Map.of("tag", "markdown", "content", markdown))));
    }

    private Attempt classify(BaseResponse<?> response, String messageId) {
        if (response == null) return Attempt.failed(Status.UNKNOWN, "empty_response");
        int httpStatus = response.getRawResponse() == null ? 0 : response.getRawResponse().getStatusCode();
        if (httpStatus >= 500) return Attempt.failed(Status.UNKNOWN, "http_" + httpStatus);
        if (response.success()) return messageId == null || messageId.isBlank()
                ? Attempt.failed(Status.UNKNOWN, "missing_message_id") : Attempt.sent(messageId);
        // 仅已知的平台频率限制响应可安全退避。5xx、网络超时等不假定未送达。
        int code = response.getCode();
        if (code == 99991400 || code == 99991401 || httpStatus == 429) {
            return new Attempt(Status.RETRYABLE, null, Integer.toString(code), retryAfter(response));
        }
        return Attempt.failed(Status.FAILED, Integer.toString(code));
    }

    private long retryAfter(BaseResponse<?> response) {
        if (response.getRawResponse() == null || response.getRawResponse().getHeaders() == null) return 0;
        return response.getRawResponse().getHeaders().entrySet().stream()
                .filter(entry -> "Retry-After".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream()).findFirst().map(value -> {
                    try { return Math.min(3600L, Math.max(0, Long.parseLong(value))) * 1000L; }
                    catch (NumberFormatException exception) {
                        try {
                            long date = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                                    .toInstant().toEpochMilli();
                            return Math.min(3_600_000L, Math.max(0, date - System.currentTimeMillis()));
                        } catch (java.time.format.DateTimeParseException invalidDate) { return 0L; }
                    }
                }).orElse(0L);
    }
}
