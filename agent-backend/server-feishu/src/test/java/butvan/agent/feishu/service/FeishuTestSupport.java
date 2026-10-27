package butvan.agent.feishu.service;

import butvan.agent.feishu.config.FeishuConfigData;
import com.lark.oapi.channel.model.NormalizedMessage;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static butvan.agent.feishu.service.FeishuTaskStore.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FeishuTestSupport {
    static FeishuConfigData config() { return new FeishuConfigData(true, "test-app", "test-secret", List.of("owner"), 60, 600, 600, 5, 2); }
    static Task task(String id, String conversation, Execution state, long now) {
        return Task.builder().id(id).conversation(conversation).chatId(conversation).replyTo("msg-" + id)
                .senderId("owner").runId(UUID.randomUUID().toString()).turnId(UUID.randomUUID().toString())
                .input("test").acceptedAt(now).state(state).build();
    }
    static NormalizedMessage message(String id, String text) { return message(id, text, "chat", "p2p", "owner"); }
    static NormalizedMessage message(String id, String text, String chat, String type, String sender) {
        return new NormalizedMessage(id, chat, type, sender, "test", text, "text", List.of(), List.of(),
                false, false, null, null, null, 0L, null);
    }
    /** 图片消息：飞书不提供正文，只给一个图片资源键。 */
    static NormalizedMessage imageMessage(String id, String fileKey) {
        return new NormalizedMessage(id, "chat", "p2p", "owner", "test", "", "image",
                List.of(new com.lark.oapi.channel.model.ResourceDescriptor("image", fileKey, "image", null)),
                List.of(), false, false, null, null, null, 0L, null);
    }
    /** 一张只带合法 PNG 文件头的小图，足以通过格式嗅探。 */
    static byte[] png() {
        byte[] header = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] data = new byte[32];
        System.arraycopy(header, 0, data, 0, header.length);
        return data;
    }
    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "异步操作未在期限内完成");
    }
    static final class TestClock extends Clock implements LongSupplier {
        private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);
        private final AtomicLong nanos = new AtomicLong();
        void advance(int seconds) { millis.addAndGet(seconds * 1000L); nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds)); }
        @Override public long getAsLong() { return nanos.get(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
        @Override public long millis() { return millis.get(); }
    }
}
