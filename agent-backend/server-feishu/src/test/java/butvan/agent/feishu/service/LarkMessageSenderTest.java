package butvan.agent.feishu.service;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LarkMessageSenderTest {
    final Client client = mock(Client.class, RETURNS_DEEP_STUBS);

    @Test void markdownReplyIsSentAsCardJsonWithStableUuid() throws Exception {
        ReplyMessageResp response = mock(ReplyMessageResp.class, RETURNS_DEEP_STUBS);
        when(response.success()).thenReturn(true);
        when(response.getData().getMessageId()).thenReturn("platform-id");
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(response);
        var sender = new LarkMessageSender(client);
        String markdown = "## 标题\n\n- 列表 `code` \"引号\"\n\n```java\nint a = 1;\n```\n末尾🙂";
        assertEquals(FeishuMessageSender.Status.SENT, sender.send("chat", "parent", "stable-uuid", markdown,
                FeishuMessageSender.Format.MARKDOWN).status());
        ArgumentCaptor<ReplyMessageReq> request = ArgumentCaptor.forClass(ReplyMessageReq.class);
        verify(client.im().message()).reply(request.capture());
        ReplyMessageReqBody body = request.getValue().getReplyMessageReqBody();
        assertEquals("stable-uuid", body.getUuid());
        assertEquals("interactive", body.getMsgType());
        var card = com.lark.oapi.core.utils.Jsons.DEFAULT.fromJson(body.getContent(), java.util.Map.class);
        assertEquals("2.0", card.get("schema"));
        var elements = (java.util.List<?>) ((java.util.Map<?, ?>) card.get("body")).get("elements");
        var element = (java.util.Map<?, ?>) elements.getFirst();
        assertEquals("markdown", element.get("tag"));
        // 转义正确才能完整还原正文，否则卡片会把 Markdown 当成被截断的普通文本。
        assertEquals(markdown, element.get("content"));
    }

    @Test void receiptNoticeStaysPlainText() throws Exception {
        ReplyMessageResp response = mock(ReplyMessageResp.class, RETURNS_DEEP_STUBS);
        when(response.success()).thenReturn(true);
        when(response.getData().getMessageId()).thenReturn("platform-id");
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(response);
        new LarkMessageSender(client).send("chat", "parent", "uuid", "已收到，正在处理…", FeishuMessageSender.Format.TEXT);
        ArgumentCaptor<ReplyMessageReq> request = ArgumentCaptor.forClass(ReplyMessageReq.class);
        verify(client.im().message()).reply(request.capture());
        assertEquals("text", request.getValue().getReplyMessageReqBody().getMsgType());
        assertTrue(request.getValue().getReplyMessageReqBody().getContent().contains("已收到，正在处理…"));
    }

    @Test void unknownNetworkFailureDoesNotCreateFallbackOrRetry() throws Exception {
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenThrow(new java.io.IOException("secret-body"));
        assertEquals(FeishuMessageSender.Status.UNKNOWN, new LarkMessageSender(client).send("chat", "parent", "uuid", "text", FeishuMessageSender.Format.TEXT).status());
        verify(client.im().message(), times(1)).reply(any(ReplyMessageReq.class));
        verify(client.im().message(), never()).create(any(CreateMessageReq.class));
    }

    @Test void serverErrorIsUnknownAndRateLimitHonorsRetryAfter() throws Exception {
        ReplyMessageResp response = mock(ReplyMessageResp.class);
        var raw = new com.lark.oapi.core.response.RawResponse(); raw.setStatusCode(503);
        when(response.getRawResponse()).thenReturn(raw); when(response.getCode()).thenReturn(99991400);
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(response);
        LarkMessageSender sender = new LarkMessageSender(client);
        assertEquals(FeishuMessageSender.Status.UNKNOWN, sender.send("chat", "parent", "id", "text", FeishuMessageSender.Format.MARKDOWN).status());
        raw.setStatusCode(429); raw.setHeaders(java.util.Map.of("retry-after", java.util.List.of("30")));
        var attempt = sender.send("chat", "parent", "id", "text", FeishuMessageSender.Format.MARKDOWN);
        assertEquals(FeishuMessageSender.Status.RETRYABLE, attempt.status()); assertEquals(30_000, attempt.retryAfterMillis());
    }

    @Test void explicitRateLimitIsRetryableButOtherPlatformErrorsAreNot() throws Exception {
        ReplyMessageResp response = mock(ReplyMessageResp.class);
        when(response.success()).thenReturn(false); when(response.getCode()).thenReturn(99991400, 230011);
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(response);
        LarkMessageSender sender = new LarkMessageSender(client);
        assertEquals(FeishuMessageSender.Status.RETRYABLE, sender.send("chat", "parent", "uuid", "text", FeishuMessageSender.Format.TEXT).status());
        assertEquals(FeishuMessageSender.Status.FAILED, sender.send("chat", "parent", "uuid", "text", FeishuMessageSender.Format.TEXT).status());
    }
}
