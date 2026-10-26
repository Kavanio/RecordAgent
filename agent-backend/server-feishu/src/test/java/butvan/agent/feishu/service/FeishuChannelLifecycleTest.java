package butvan.agent.feishu.service;

import butvan.agent.feishu.config.FeishuProperties;
import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.model.BotIdentity;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import static butvan.agent.feishu.service.FeishuTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuChannelLifecycleTest {
    @Test void slowConnectionDoesNotBlockMainStartupAndLateConnectionIsClosed() throws Exception {
        FeishuProperties properties = mock(FeishuProperties.class);
        when(properties.load()).thenReturn(config());
        FeishuChatService chat = mock(FeishuChatService.class);
        LarkChannel channel = mock(LarkChannel.class);
        CompletableFuture<BotIdentity> connection = new CompletableFuture<>();
        when(channel.connect()).thenReturn(connection);
        when(channel.disconnect()).thenAnswer(call -> CompletableFuture.completedFuture(null));
        FeishuChannelLifecycle lifecycle = new FeishuChannelLifecycle(properties, chat, ignored -> channel, 30_000);
        try {
            assertTimeout(Duration.ofMillis(300), lifecycle::start);
            // connect 是 synchronized 方法，Mockito 的 timeout 验证会持锁阻塞工作线程。
            await(() -> mockingDetails(channel).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("connect")));
            await(() -> connection.getNumberOfDependents() > 0);
            lifecycle.stop();
            long beforeLateConnection = mockingDetails(channel).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("disconnect")).count();
            connection.complete(mock(BotIdentity.class));
            await(() -> mockingDetails(channel).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("disconnect")).count() > beforeLateConnection);
            assertFalse(lifecycle.isRunning());
        } finally { lifecycle.stop(); }
    }

    @Test void oldConfigWithoutOwnerWhitelistDoesNotStartAnything() {
        FeishuProperties properties = mock(FeishuProperties.class);
        when(properties.load()).thenReturn(butvan.agent.feishu.config.FeishuConfigData.disabled());
        FeishuChatService chat = mock(FeishuChatService.class);
        FeishuChannelLifecycle lifecycle = new FeishuChannelLifecycle(properties, chat);
        lifecycle.start(); assertFalse(lifecycle.isRunning()); verifyNoInteractions(chat);
    }

    @Test void initializationFailureDoesNotLeaveLifecycleRunning() throws Exception {
        FeishuProperties properties = mock(FeishuProperties.class); when(properties.load()).thenReturn(config());
        FeishuChatService chat = mock(FeishuChatService.class);
        doThrow(new java.io.IOException("test storage failure")).when(chat).open(any(), any());
        FeishuChannelLifecycle lifecycle = new FeishuChannelLifecycle(properties, chat);
        lifecycle.start();
        verify(chat, timeout(1500)).close();
        assertFalse(lifecycle.isRunning());
        lifecycle.stop();
    }
}
