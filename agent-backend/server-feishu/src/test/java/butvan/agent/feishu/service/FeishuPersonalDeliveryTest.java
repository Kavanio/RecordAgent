package butvan.agent.feishu.service;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.session.*;
import butvan.agent.agents.review.LifeReviewReplyPort;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static butvan.agent.feishu.service.FeishuTestSupport.*;

/** 主动复盘只向固定本人发送；主题补充不执行聊天 Agent。 */
class FeishuPersonalDeliveryTest {
    @TempDir Path directory;
    FeishuChatService chat;
    FeishuTaskStore store;
    FeishuMessageSender sender;
    FeishuAgentRunner runner;
    @BeforeEach void setup() throws Exception {
        var owner = mock(CurrentUserProvider.class); when(owner.currentUserId()).thenReturn("owner");
        runner = mock(FeishuAgentRunner.class);
        var model = mock(ModelHolder.class); when(model.isInitialized()).thenReturn(true);
        chat = new FeishuChatService(runner, mock(SessionCatalogService.class), mock(TranscriptService.class), model, owner);
        store = new FeishuTaskStore(directory);
        sender = mock(FeishuMessageSender.class);
        when(sender.send(anyString(), any(), anyString(), anyString(), any())).thenReturn(FeishuMessageSender.Attempt.sent("notice"));
        when(sender.sendToOwner(anyString(), anyString(), anyString())).thenReturn(FeishuMessageSender.Attempt.sent("push"));
        var clock = new TestClock();
        chat.open(config(), sender, (id, key) -> png(), store, clock, clock, false);
    }
    @AfterEach void close() throws Exception { chat.close(); store.close(); }
    @Test void fixedRecipientAndFingerprintPreventTargetSubstitution() {
        var target = chat.target(); assertTrue(target.available());
        assertEquals("FAILED", chat.send("other", "delivery", "虚构复盘").state());
        verify(sender, never()).sendToOwner(anyString(), anyString(), anyString());
        assertEquals("SENT", chat.send(target.fingerprint(), "delivery", "虚构复盘").state());
        verify(sender).sendToOwner(eq(config().allowedSenderIds().getFirst()), eq("delivery"), eq("虚构复盘"));
    }
    @Test void multiParagraphReviewSendsSegmentsOneByOneInOrder() {
        var target = chat.target();
        assertEquals("SENT", chat.send(target.fingerprint(), "seg-delivery", "第一句开场\n\n第二句回顾\n\n第三句建议").state());
        var order = inOrder(sender);
        order.verify(sender).sendToOwner(eq(config().allowedSenderIds().getFirst()), eq("seg-delivery"), eq("第一句开场"));
        order.verify(sender).sendToOwner(eq(config().allowedSenderIds().getFirst()), anyString(), eq("第二句回顾"));
        order.verify(sender).sendToOwner(eq(config().allowedSenderIds().getFirst()), anyString(), eq("第三句建议"));
    }
    @Test void unknownStatePropagatesAndClosedChannelKeepsKnownIdentityWithoutSending() throws Exception {
        var target = chat.target();
        when(sender.sendToOwner(anyString(), anyString(), anyString())).thenReturn(FeishuMessageSender.Attempt.failed(FeishuMessageSender.Status.UNKNOWN, "timeout"));
        assertEquals("UNKNOWN", chat.send(target.fingerprint(), "d", "虚构").state());
        chat.close();
        assertFalse(chat.target().available()); assertEquals(target.fingerprint(), chat.target().fingerprint());
        assertEquals("FAILED", chat.send(target.fingerprint(), "d2", "虚构").state());
        verify(sender, times(1)).sendToOwner(anyString(), anyString(), anyString());
    }
    @Test void reviewReplyUsesContentPortAndDoesNotStartAgent() throws Exception {
        LifeReviewReplyPort port = mock(LifeReviewReplyPort.class);
        when(port.reply(anyString(), anyString(), anyString(), anyString())).thenReturn("已记录新版本");
        var factory = new StaticListableBeanFactory(); factory.addBean("review", port);
        var field = FeishuChatService.class.getDeclaredField("reviewReplies"); field.setAccessible(true);
        field.set(chat, factory.getBeanProvider(LifeReviewReplyPort.class));
        String id = "00000000-0000-0000-0000-000000000001";
        chat.handle(message("reply", "/review " + id + " 今天补充的虚构经历"));
        verify(port).reply(eq("owner"), eq(id), anyString(), eq("今天补充的虚构经历"));
        chat.handle(message("reply-latest", "/review 免UUID直接补充今日复盘"));
        verify(port).reply(eq("owner"), eq("latest"), anyString(), eq("免UUID直接补充今日复盘"));
        verifyNoInteractions(runner);
    }
}
