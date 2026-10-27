package butvan.agent.feishu.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static butvan.agent.feishu.service.FeishuTaskStore.*;
import static butvan.agent.feishu.service.FeishuTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class FeishuDeliveryServiceTest {
    @TempDir Path directory;
    final TestClock clock = new TestClock();

    Task result(String id) {
        return task(id, "chat", Execution.SUCCEEDED, clock.millis()).toBuilder()
                .parts(List.of(Part.builder().id("stable-" + id + "-0").text("第一段").status(Delivery.PENDING).build(),
                        Part.builder().id("stable-" + id + "-1").text("尾字🙂").status(Delivery.PENDING).build())).build();
    }

    @Test void partsAreSentInOrderAndConfirmedIndividually() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 sent.add(text); return FeishuMessageSender.Attempt.sent(uuid);
             }, clock)) {
            store.accept(result("first"), 5, clock.millis());
            delivery.pump(); await(() -> store.task("first").parts().getFirst().status() == Delivery.SENT);
            assertEquals(List.of("第一段"), sent);
            await(() -> { delivery.pump(); return store.task("first").parts().getLast().status() == Delivery.SENT; });
            delivery.pump();
            assertEquals(List.of("第一段", "尾字🙂"), sent);
        }
    }

    @Test void finalPartsAreMarkdownWhileHeartbeatsStayPlainText() throws Exception {
        List<String> formats = new CopyOnWriteArrayList<>();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 formats.add(format + ":" + text); return FeishuMessageSender.Attempt.sent(uuid);
             }, clock)) {
            store.accept(result("first"), 5, clock.millis());
            delivery.notice(store.task("first"), "heartbeat", "仍在处理", false, () -> true);
            await(() -> { delivery.pump(); return formats.size() >= 3; });
            assertTrue(formats.contains("MARKDOWN:第一段"));
            assertTrue(formats.contains("MARKDOWN:尾字🙂"));
            assertTrue(formats.contains("TEXT:仍在处理"));
        }
    }

    @Test void rateLimitRetryKeepsStableIdAndDoesNotResendSuccessfulParts() throws Exception {
        List<String> ids = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 ids.add(uuid);
                 return calls.incrementAndGet() == 1 ? FeishuMessageSender.Attempt.failed(FeishuMessageSender.Status.RETRYABLE, "rate_limit")
                         : FeishuMessageSender.Attempt.sent(uuid);
             }, clock)) {
            store.accept(result("first"), 5, clock.millis());
            delivery.pump(); await(() -> store.task("first").parts().getFirst().status() == Delivery.RETRY_WAIT);
            delivery.pump(); assertEquals(1, calls.get());
            clock.advance(1);
            await(() -> { delivery.pump(); return store.task("first").parts().getFirst().status() == Delivery.SENT; });
            assertEquals(List.of("stable-first-0", "stable-first-0"), ids.stream().filter(id -> id.equals("stable-first-0")).toList());
            await(() -> { delivery.pump(); return store.task("first").parts().getLast().status() == Delivery.SENT; });
            assertEquals(3, calls.get());
        }
    }

    @Test void unknownDeliveryBlocksLaterPartsAndRepliesUntilExplicitResend() throws Exception {
        List<String> finalIds = new CopyOnWriteArrayList<>();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 if (text.equals("第一段") || text.equals("尾字🙂")) finalIds.add(uuid);
                 return uuid.equals("stable-first-0") ? FeishuMessageSender.Attempt.failed(FeishuMessageSender.Status.UNKNOWN, "timeout")
                         : FeishuMessageSender.Attempt.sent(uuid);
             }, clock)) {
            store.accept(result("first"), 5, clock.millis());
            store.accept(result("second"), 5, clock.millis());
            delivery.pump(); await(() -> store.task("first").parts().getFirst().status() == Delivery.UNKNOWN);
            delivery.pump(); clock.advance(60); delivery.pump();
            assertEquals(List.of("stable-first-0"), finalIds);
            delivery.retry("first", false); delivery.pump();
            assertEquals(Delivery.UNKNOWN, store.task("first").parts().getFirst().status());
            delivery.retry("first", true); delivery.pump();
            await(() -> { delivery.pump(); return store.task("first").parts().getFirst().status() == Delivery.SENT; });
            assertNotEquals("stable-first-0", finalIds.getLast());
        }
    }

    @Test void controlFeedbackBypassesAnOlderUnknownReply() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 sent.add(text); return FeishuMessageSender.Attempt.sent(uuid);
             }, clock)) {
            Task unknown = result("old").toBuilder().parts(List.of(Part.builder().id("old-part").text("unknown")
                    .status(Delivery.UNKNOWN).build())).build();
            store.accept(unknown, 5, clock.millis());
            store.accept(task("command", "chat", Execution.SUCCEEDED, clock.millis()).toBuilder().stage("控制反馈")
                    .parts(List.of(Part.builder().id("command-part").text("正在停止").status(Delivery.PENDING).build())).build(), 5, clock.millis());
            delivery.pump(); await(() -> sent.contains("正在停止")); assertFalse(sent.contains("unknown"));
        }
    }

    @Test void invalidatedHeartbeatNeverStartsSending() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 sent.incrementAndGet(); return FeishuMessageSender.Attempt.sent(uuid);
             }, clock)) {
            Task task = task("first", "chat", Execution.RUNNING, clock.millis());
            store.accept(task, 5, clock.millis());
            delivery.notice(task, "heartbeat", "仍在处理", false, () -> false);
            delivery.pump();
            assertEquals(0, sent.get());
        }
    }

    @Test void eachHeartbeatHasItsOwnPlatformDeduplicationId() throws Exception {
        List<String> ids = new CopyOnWriteArrayList<>();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 ids.add(uuid); return FeishuMessageSender.Attempt.sent(uuid);
             }, clock)) {
            Task task = task("first", "chat", Execution.RUNNING, clock.millis());
            store.accept(task, 5, clock.millis());
            delivery.notice(task, "heartbeat", "仍在处理，1 分钟", false, () -> true);
            delivery.pump(); await(() -> ids.size() == 1);
            clock.advance(60);
            delivery.notice(task, "heartbeat", "仍在处理，2 分钟", false, () -> true);
            await(() -> { delivery.pump(); return ids.size() == 2; });
            assertNotEquals(ids.getFirst(), ids.getLast(), "不同提醒不能被平台当作同一条消息去重");
        }
    }

    @Test void boundedRetryStopsAfterFourAttempts() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (FeishuTaskStore store = new FeishuTaskStore(directory);
             FeishuDeliveryService delivery = new FeishuDeliveryService(store, (chat, reply, uuid, text, format) -> {
                 if (text.equals("第一段")) attempts.incrementAndGet();
                 return FeishuMessageSender.Attempt.failed(FeishuMessageSender.Status.RETRYABLE, "rate_limit");
             }, clock)) {
            store.accept(result("first"), 5, clock.millis());
            for (int i = 1; i <= 4; i++) {
                int expected = i;
                delivery.pump();
                await(() -> { delivery.pump(); return store.task("first").parts().getFirst().attempts() == expected
                        && store.task("first").parts().getFirst().status() != Delivery.SENDING; });
                clock.advance(30);
            }
            assertEquals(Delivery.FAILED, store.task("first").parts().getFirst().status());
            delivery.pump(); assertEquals(4, attempts.get());
        }
    }
}
