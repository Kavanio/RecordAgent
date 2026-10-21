package butvan.agent.network.automation;

import butvan.agent.network.automation.dto.AutomationDtos.Snapshot;
import butvan.agent.network.automation.dto.AutomationDtos.TaskView;
import butvan.agent.network.automation.event.TasksChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import butvan.agent.network.automation.service.AutomationService;
import butvan.agent.network.automation.service.AutomationStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.*;

/** 通过公开订阅入口验证长连接生命周期，不访问用户数据库。 */
class AutomationStreamTest {
    @Test
    void subscriptionDoesNotExpireEveryTwoMinutes() {
        var stream = new AutomationStream(new SnapshotSource());
        try {
            var emitter = stream.subscribe("owner");
            assertEquals(0L, emitter.getTimeout());
        } finally { stream.close(); }
    }

    @Test
    void quietConnectionReceivesHeartbeatWithoutReloadingSnapshot() throws Exception {
        var source = new SnapshotSource();
        var stream = new AutomationStream(source);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new StreamEndpoint(stream)).build();
            var result = mvc.perform(get("/stream")).andReturn();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertTrue(result.getResponse().getContentAsString().contains("event:snapshot")));
            int reads = source.reads;
            await().atMost(Duration.ofSeconds(18)).untilAsserted(() ->
                    assertTrue(result.getResponse().getContentAsString().contains("event:heartbeat")));
            assertEquals(reads, source.reads, "心跳不得重新查询任务与执行历史");
        } finally { stream.close(); }
    }

    @Test
    void slowConsumerDoesNotBlockDomainWritesAndReceivesLatestState() throws Exception {
        var source = new SnapshotSource();
        source.value = snapshot(1, 0);
        var stream = new AutomationStream(source);
        var block = new AtomicBoolean();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new StreamEndpoint(stream)).addFilters((request, response, chain) ->
                    chain.doFilter(request, new HttpServletResponseWrapper((HttpServletResponse) response) {
                        @Override public ServletOutputStream getOutputStream() throws IOException {
                            var delegate = super.getOutputStream();
                            return new ServletOutputStream() {
                                @Override public boolean isReady() { return delegate.isReady(); }
                                @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
                                @Override public void write(int value) throws IOException {
                                    if (block.get()) {
                                        entered.countDown();
                                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("slow consumer timed out"); }
                                        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                                    }
                                    delegate.write(value);
                                }
                            };
                        }
                    })).build();
            var response = mvc.perform(get("/stream")).andReturn().getResponse();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertTrue(response.getContentAsString().contains("event:snapshot")));
            block.set(true);
            source.value = snapshot(2, 5);
            stream.changed(new TasksChanged("owner", true));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture.runAsync(() -> {
                synchronized (source) {
                    source.value = snapshot(3, 10);
                    stream.changed(new TasksChanged("owner"));
                    source.value = snapshot(3, 15);
                    stream.changed(new TasksChanged("owner", true));
                }
            }).get(1, TimeUnit.SECONDS);
            block.set(false); release.countDown();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                String body = response.getContentAsString();
                assertTrue(body.contains("\"activeSeconds\":15"));
                String lastData = body.substring(body.lastIndexOf("data:") + 5).trim();
                var last = new ObjectMapper().readTree(lastData);
                assertEquals(3, last.path("tasks").get(0).path("version").asInt());
                assertFalse(last.path("activityOnly").asBoolean(), "纯计时快照不能吞掉未投递的状态变化");
            });
        } finally { block.set(false); release.countDown(); stream.close(); }
    }

    @Test
    void broadcastsOneFrozenSnapshotAndCompletesStreamsOnShutdown() throws Exception {
        var source = new SnapshotSource();
        var stream = new AutomationStream(source);
        var first = stream.subscribe("owner");
        var second = stream.subscribe("owner");
        try {
            int reads = source.reads;
            stream.changed(new TasksChanged("owner"));
            assertEquals(reads + 1, source.reads, "同一事件只读取一次权威快照");
        } finally { stream.close(); }
        assertThrows(IllegalStateException.class, () -> first.send(SseEmitter.event().data("late")));
        assertThrows(IllegalStateException.class, () -> second.send(SseEmitter.event().data("late")));
        var late = stream.subscribe("owner");
        assertThrows(IllegalStateException.class, () -> late.send(SseEmitter.event().data("late")));
    }

    private static Snapshot snapshot(int version, long seconds) {
        return new Snapshot(List.of(new TaskView("task", null, "ENABLED", version, null, seconds)), List.of());
    }

    @RestController
    static class StreamEndpoint {
        private final AutomationStream stream;
        StreamEndpoint(AutomationStream stream) { this.stream = stream; }
        @GetMapping(value = "/stream", produces = "text/event-stream")
        public SseEmitter subscribe() { return stream.subscribe("owner"); }
    }

    /** 实时流读取 seam 的内存 Adapter；领域写入另由 SQLite 集成测试覆盖。 */
    static class SnapshotSource extends AutomationService {
        volatile int reads;
        volatile Snapshot value = new Snapshot(List.of(), List.of());
        SnapshotSource() { super(null, null, null, event -> { }, null, Clock.systemUTC()); }
        @Override public synchronized Snapshot snapshot(String owner) { reads++; return value; }
    }
}
