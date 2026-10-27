package butvan.agent.feishu.service;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static butvan.agent.feishu.service.FeishuTaskStore.*;
import static butvan.agent.feishu.service.FeishuTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 覆盖真实受理、调度、持久化和投递的端到端渠道行为，不连接真实飞书。 */
class FeishuChatServiceTest {
    @TempDir Path directory;
    FeishuAgentRunner runner;
    SessionCatalogService sessions;
    TranscriptService transcripts;
    ModelHolder model;
    FeishuTaskStore store;
    FeishuChatService chat;
    final TestClock clock = new TestClock();
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<String> inputs = new CopyOnWriteArrayList<>();
    final List<List<butvan.agent.agents.agent.AgentImageAttachment>> received = new CopyOnWriteArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    /** 测试用的图片下载 seam：默认返回一张合法 PNG，可在单个用例里改成失败或非法格式。 */
    volatile byte[] imageBytes = FeishuTestSupport.png();
    String imageFetchError;
    final java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach void prepare() throws Exception {
        runner = mock(FeishuAgentRunner.class); sessions = mock(SessionCatalogService.class);
        transcripts = mock(TranscriptService.class); model = mock(ModelHolder.class);
        CurrentUserProvider owner = mock(CurrentUserProvider.class);
        when(owner.currentUserId()).thenReturn("local-owner");
        when(model.isInitialized()).thenReturn(true);
        when(transcripts.list(anyString())).thenReturn(List.of());
        SessionSummaryDto summary = mock(SessionSummaryDto.class); when(summary.id()).thenReturn("session");
        when(sessions.create(any())).thenReturn(summary);
        store = new FeishuTaskStore(directory);
        chat = new FeishuChatService(runner, sessions, transcripts, model, owner);
        when(runner.run(anyString(), anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            String input = invocation.getArgument(3); inputs.add(input);
            received.add(invocation.getArgument(4));
            if (input.equals("first")) assertTrue(release.await(3, TimeUnit.SECONDS));
            return new FeishuAgentRunner.Reply(new AgentTurnResult(invocation.getArgument(1), invocation.getArgument(0),
                    invocation.getArgument(2), TranscriptMessageDto.MessageStatus.COMPLETED, "answer:" + input), null, "answer:" + input);
        });
    }

    void open() { chat.open(config(), (target, parent, id, text, format) -> {
        sent.add(text); return FeishuMessageSender.Attempt.sent(id);
    }, (messageId, fileKey) -> {
        downloads.incrementAndGet();
        if (imageFetchError != null) throw new IllegalStateException(imageFetchError);
        return imageBytes;
    }, store, clock, clock, false); }

    @AfterEach void close() throws Exception { release.countDown(); chat.close(); store.close(); }

    void pumpUntil(BooleanSupplier condition) throws Exception {
        await(() -> { chat.tick(); return condition.getAsBoolean(); });
    }

    Task byMessage(String message) {
        return store.task(stableId("test-app:local-owner:" + message));
    }

    @Test void immediateReceiptPeriodicHeartbeatAndFinalReply() throws Exception {
        open(); chat.handle(message("one", "first"));
        await(() -> inputs.size() == 1 && sent.stream().anyMatch(s -> s.startsWith("已收到")));
        clock.advance(59); chat.tick();
        assertEquals(0, sent.stream().filter(s -> s.startsWith("任务仍在处理")).count());
        clock.advance(1); chat.tick();
        pumpUntil(() -> sent.stream().filter(s -> s.startsWith("任务仍在处理")).count() == 1);
        clock.advance(60); chat.tick();
        pumpUntil(() -> sent.stream().filter(s -> s.startsWith("任务仍在处理")).count() == 2);
        release.countDown();
        pumpUntil(() -> sent.contains("answer:first"));
        clock.advance(120); chat.tick();
        assertEquals(2, sent.stream().filter(s -> s.startsWith("任务仍在处理")).count());
        assertEquals(Execution.SUCCEEDED, byMessage("one").state());
        verify(runner, times(1)).run(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test void receiptTellsUserHowToUnblockAConversationPausedByUnknownDelivery() throws Exception {
        String conversation = stableId("test-app:local-owner:chat::owner");
        store.accept(task("stale", conversation, Execution.SUCCEEDED, clock.millis()).toBuilder().chatId("chat")
                .parts(List.of(Part.builder().id("stale-0").text("旧回复").status(Delivery.UNKNOWN).build())).build(),
                5, clock.millis());
        open(); chat.handle(message("again", "hello again"));
        // UNKNOWN 会按设计暂停该会话的后续回复；回执必须直接给出 /resend，而不是静默停摆。
        await(() -> sent.stream().anyMatch(text -> text.startsWith("已收到") && text.contains("/resend stale")));
    }

    @Test void ackClearsUnknownDeliveryWithoutResendingAndUnblocksConversation() throws Exception {
        String conversation = stableId("test-app:local-owner:chat::owner");
        String staleId = "00000000-0000-0000-0000-0000000000aa";
        store.accept(task(staleId, conversation, Execution.SUCCEEDED, clock.millis()).toBuilder().chatId("chat")
                .parts(List.of(Part.builder().id("stale-0").text("旧回复").status(Delivery.UNKNOWN).build())).build(),
                5, clock.millis());
        open(); chat.handle(message("ack", "/ack " + staleId));
        pumpUntil(() -> store.task(staleId).parts().getFirst().status() == Delivery.SENT);
        assertFalse(sent.contains("旧回复"));
        chat.handle(message("next", "hello"));
        pumpUntil(() -> sent.contains("answer:hello"));
    }

    @Test void imageMessageWithVisionModelReachesAgentAsAttachment() throws Exception {
        when(model.supportsVision()).thenReturn(true);
        open(); chat.handle(imageMessage("pic", "file-key"));
        await(() -> !received.isEmpty());
        assertEquals(1, received.getFirst().size());
        assertEquals("image/png", received.getFirst().getFirst().mediaType());
        assertEquals("用户发送了一张图片，请识别并回应图片内容。", inputs.getFirst());
        assertTrue(sent.stream().anyMatch(text -> text.startsWith("已收到")));
    }

    @Test void imageWithoutVisionCapabilityIsRejectedBeforeCallingTheModel() throws Exception {
        when(model.supportsVision()).thenReturn(false);
        open(); chat.handle(imageMessage("pic", "file-key"));
        pumpUntil(() -> sent.stream().anyMatch(text -> text.contains("图片识别")));
        assertTrue(inputs.isEmpty());
        verifyNoInteractions(runner);
    }

    @Test void unsupportedImageFormatFailsWithActionableMessage() throws Exception {
        when(model.supportsVision()).thenReturn(true);
        imageBytes = "not-an-image-at-all".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        open(); chat.handle(imageMessage("pic", "file-key"));
        pumpUntil(() -> sent.stream().anyMatch(text -> text.contains("图片读取失败")));
        assertTrue(inputs.isEmpty());
    }

    @Test void imageDownloadFailureDoesNotRetryOrRunTheModel() throws Exception {
        when(model.supportsVision()).thenReturn(true);
        imageFetchError = "download failed";
        open(); chat.handle(imageMessage("pic", "file-key"));
        pumpUntil(() -> sent.stream().anyMatch(text -> text.contains("图片读取失败")));
        assertTrue(inputs.isEmpty());
    }

    @Test void rejectsMixedPostWithoutDroppingTextOrDownloadingJustTheFirstImage() throws Exception {
        when(model.supportsVision()).thenReturn(true);
        var source = imageMessage("mixed", "file-key");
        var post = new com.lark.oapi.channel.model.NormalizedMessage("mixed", "chat", "p2p", "owner", "test",
                "请比较这两张图，不要只看第一张", "post", source.getResources(), List.of(),
                false, false, null, null, null, 0L, null);
        open(); chat.handle(post);
        pumpUntil(() -> sent.stream().anyMatch(text -> text.contains("暂不支持图文混排")));
        assertEquals(0, downloads.get());
        verifyNoInteractions(runner);
    }

    @Test void missingImageKeyIsReportedInsteadOfStartingAnEmptyTurn() throws Exception {
        var missing = new com.lark.oapi.channel.model.NormalizedMessage("missing", "chat", "p2p", "owner", "test",
                "", "image", List.of(), List.of(), false, false, null, null, null, 0L, null);
        open(); chat.handle(missing);
        pumpUntil(() -> sent.stream().anyMatch(text -> text.contains("未取得图片资源")));
        assertEquals(0, downloads.get());
        verifyNoInteractions(runner);
    }

    @Test void queuedImageRechecksCapabilityBeforeDownloading() throws Exception {
        when(model.supportsVision()).thenReturn(true);
        open(); chat.handle(message("first", "first"));
        await(() -> inputs.size() == 1);
        chat.handle(imageMessage("queued-image", "file-key"));
        when(model.supportsVision()).thenReturn(false);
        release.countDown();
        pumpUntil(() -> sent.stream().anyMatch(text -> text.contains("切换到视觉模型后重发")));
        assertEquals(0, downloads.get());
        assertEquals(List.of("first"), inputs);
    }

    @Test void repeatedMessageExecutesOnceAndFollowupWaitsInFifoOrder() throws Exception {
        open(); chat.handle(message("one", "first"));
        await(() -> inputs.size() == 1);
        chat.handle(message("one", "first")); chat.handle(message("two", "second"));
        assertEquals(List.of("first"), inputs);
        assertEquals(Execution.QUEUED, byMessage("two").state());
        clock.advance(60); chat.tick();
        assertEquals(0, sent.stream().filter(s -> s.startsWith("任务仍在处理") && s.contains("second")).count());
        release.countDown();
        pumpUntil(() -> sent.contains("answer:second"));
        assertEquals(List.of("first", "second"), inputs);
        assertTrue(sent.indexOf("answer:first") < sent.indexOf("answer:second"));
        assertEquals(2, store.snapshot().tasks().size());
    }

    @Test void strangersAndGroupsCannotCreateTasksOrTriggerModel() {
        open(); chat.handle(message("stranger", "hi", "chat", "p2p", "outsider"));
        chat.handle(message("group", "hi", "group", "group", "owner"));
        assertTrue(store.snapshot().tasks().isEmpty()); assertTrue(sent.isEmpty()); verifyNoInteractions(runner);
    }

    @Test void noModelReturnsClearFailureWithoutClaimingToBeProcessing() throws Exception {
        when(model.isInitialized()).thenReturn(false);
        open(); chat.handle(message("one", "hello"));
        pumpUntil(() -> !sent.isEmpty());
        assertTrue(sent.getFirst().contains("模型尚未配置"));
        assertFalse(sent.stream().anyMatch(s -> s.startsWith("已收到"))); verifyNoInteractions(runner);
    }

    @Test void clearAndStopBypassFullWaitingQueue() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
            inputs.add(invocation.getArgument(3)); started.countDown();
            FeishuAgentRunner.Control control = invocation.getArgument(6);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!control.stopping() && release.getCount() > 0 && System.nanoTime() < end) Thread.sleep(5);
            return new FeishuAgentRunner.Reply(new AgentTurnResult(invocation.getArgument(1), "session", invocation.getArgument(2),
                    TranscriptMessageDto.MessageStatus.CANCELLED, "partial"), control.reason(), "任务已停止。");
        }).when(runner).run(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
        open(); chat.handle(message("one", "long")); assertTrue(started.await(1, TimeUnit.SECONDS));
        for (int i = 0; i < 5; i++) chat.handle(message("queued-" + i, "next-" + i));
        chat.handle(message("overflow", "too-many")); assertNull(byMessage("overflow"));
        chat.handle(message("clear", "/clear")); chat.handle(message("stop", "/stop"));
        pumpUntil(() -> byMessage("one").state() == Execution.CANCELLED);
        assertEquals(List.of("long"), inputs);
        for (int i = 0; i < 5; i++) assertEquals(Execution.CLEARED, byMessage("queued-" + i).state());
    }

    @Test void restartUsesExactSavedTurnAndNeverRunsAgentAgain() throws Exception {
        Task crashed = task("crashed", "chat", Execution.RUNNING, clock.millis()).toBuilder()
                .sessionId("session").turnId("exact-turn").build();
        store.accept(crashed, 5, clock.millis());
        when(transcripts.list("session")).thenReturn(List.of(new TranscriptMessageDto("assistant", "exact-turn",
                TranscriptMessageDto.MessageRole.ASSISTANT, "已落盘的完整尾字🙂", Instant.now(),
                TranscriptMessageDto.MessageStatus.COMPLETED, null, List.of(), null, null)));
        open(); pumpUntil(() -> sent.contains("已落盘的完整尾字🙂"));
        verifyNoInteractions(runner); assertEquals(Execution.SUCCEEDED, store.task("crashed").state());
    }

    @Test void restartDoesNotReplayQueuedTasksOrUnknownSends() throws Exception {
        store.accept(task("queued", "chat", Execution.QUEUED, clock.millis()), 5, clock.millis());
        store.accept(task("sending", "other", Execution.SUCCEEDED, clock.millis()).toBuilder()
                .parts(List.of(Part.builder().id("part").text("可能已送达的正文").status(Delivery.SENDING).build())).build(), 5, clock.millis());
        open(); chat.tick();
        assertEquals(Execution.INTERRUPTED, store.task("queued").state());
        assertEquals(Delivery.UNKNOWN, store.task("sending").parts().getFirst().status());
        assertFalse(sent.contains("可能已送达的正文")); verifyNoInteractions(runner);
    }

    @Test void expiredQueuedMessageDoesNotRunAfterPreviousTask() throws Exception {
        open(); chat.handle(message("one", "first")); await(() -> inputs.size() == 1);
        chat.handle(message("two", "second")); clock.advance(601); chat.tick();
        assertEquals(Execution.EXPIRED, byMessage("two").state());
        release.countDown(); pumpUntil(() -> sent.stream().anyMatch(s -> s.contains("等待时间过长")));
        assertEquals(List.of("first"), inputs);
    }
}
