package butvan.agent.feishu.service;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LarkTextSenderTest {
    final Client client = mock(Client.class, RETURNS_DEEP_STUBS);

    @Test void replyUsesStableUuidAndSendsOneTextRequest() throws Exception {
        ReplyMessageResp response = mock(ReplyMessageResp.class, RETURNS_DEEP_STUBS);
        when(response.success()).thenReturn(true);
        when(response.getData().getMessageId()).thenReturn("platform-id");
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(response);
        var sender = new LarkTextSender(client);
        assertEquals(FeishuTextSender.Status.SENT, sender.send("chat", "parent", "stable-uuid", "末尾🙂").status());
        ArgumentCaptor<ReplyMessageReq> request = ArgumentCaptor.forClass(ReplyMessageReq.class);
        verify(client.im().message()).reply(request.capture());
        assertEquals("stable-uuid", request.getValue().getReplyMessageReqBody().getUuid());
        assertTrue(request.getValue().getReplyMessageReqBody().getContent().contains("末尾🙂"));
    }

    @Test void unknownNetworkFailureDoesNotCreateFallbackOrRetry() throws Exception {
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenThrow(new java.io.IOException("secret-body"));
        assertEquals(FeishuTextSender.Status.UNKNOWN, new LarkTextSender(client).send("chat", "parent", "uuid", "text").status());
        verify(client.im().message(), times(1)).reply(any(ReplyMessageReq.class));
        verify(client.im().message(), never()).create(any(CreateMessageReq.class));
    }

    @Test void serverErrorIsUnknownAndRateLimitHonorsRetryAfter() throws Exception {
        ReplyMessageResp response = mock(ReplyMessageResp.class);
        var raw = new com.lark.oapi.core.response.RawResponse(); raw.setStatusCode(503);
        when(response.getRawResponse()).thenReturn(raw); when(response.getCode()).thenReturn(99991400);
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(response);
        LarkTextSender sender = new LarkTextSender(client);
        assertEquals(FeishuTextSender.Status.UNKNOWN, sender.send("chat", "parent", "id", "text").status());
        raw.setStatusCode(429); raw.setHeaders(java.util.Map.of("retry-after", java.util.List.of("30")));
        var attempt = sender.send("chat", "parent", "id", "text");
        assertEquals(FeishuTextSender.Status.RETRYABLE, attempt.status()); assertEquals(30_000, attempt.retryAfterMillis());
    }

    @Test void explicitRateLimitIsRetryableButOtherPlatformErrorsAreNot() throws Exception {
        ReplyMessageResp response = mock(ReplyMessageResp.class);
        when(response.success()).thenReturn(false); when(response.getCode()).thenReturn(99991400, 230011);
        when(client.im().message().reply(any(ReplyMessageReq.class))).thenReturn(response);
        LarkTextSender sender = new LarkTextSender(client);
        assertEquals(FeishuTextSender.Status.RETRYABLE, sender.send("chat", "parent", "uuid", "text").status());
        assertEquals(FeishuTextSender.Status.FAILED, sender.send("chat", "parent", "uuid", "text").status());
    }
}
