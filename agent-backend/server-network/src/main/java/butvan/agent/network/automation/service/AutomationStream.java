package butvan.agent.network.automation.service;

import butvan.agent.network.automation.dto.AutomationDtos.Snapshot;
import butvan.agent.network.automation.event.TasksChanged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 事务提交后冻结权威快照；网络发送不占领域锁，每连接仅保留一份待发最新快照。 */
@Slf4j
@Service
public class AutomationStream {
    private final AutomationService tasks;
    private final Map<String, Set<Client>> clients = new ConcurrentHashMap<>();
    private final ExecutorService senders = Executors.newFixedThreadPool(2, runnable -> {
        var thread = new Thread(runnable, "automation-sse-send"); thread.setDaemon(true); return thread;
    });
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "automation-sse-heartbeat"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closing;

    public AutomationStream(AutomationService tasks) {
        this.tasks = tasks;
        // 心跳与业务变更分离，静默任务不查库，客户端可检测断网及代理丢失的连接。
        heartbeat.scheduleWithFixedDelay(() -> clients.values().forEach(set -> set.forEach(Client::pulse)),
                15, 15, TimeUnit.SECONDS);
    }

    /** 建连和重连先排入完整快照，不设置两分钟的请求总时长限制。 */
    public SseEmitter subscribe(String owner) {
        var emitter = new SseEmitter(0L);
        var client = new Client(owner, emitter);
        emitter.onCompletion(client::remove);
        emitter.onTimeout(client::remove);
        emitter.onError(error -> client.remove());
        synchronized (tasks) {
            if (closing) { emitter.complete(); return emitter; }
            Snapshot snapshot = tasks.snapshot(owner);
            clients.computeIfAbsent(owner, ignored -> ConcurrentHashMap.newKeySet()).add(client);
            client.offer(snapshot);
        }
        return emitter;
    }

    /** 已提交写入只负责入队；一个慢客户端不能持有任务调度锁。 */
    @TransactionalEventListener
    public void changed(TasksChanged event) {
        synchronized (tasks) {
            var subscribers = clients.getOrDefault(event.ownerId(), Set.of());
            if (closing || subscribers.isEmpty()) return;
            try {
                Snapshot snapshot = tasks.snapshot(event.ownerId());
                var update = snapshot.withActivityOnly(event.activityOnly());
                subscribers.forEach(client -> client.offer(update));
            } catch (RuntimeException error) {
                log.warn("任务快照读取失败，将断开订阅以重新同步：{}", error.getClass().getSimpleName());
                subscribers.forEach(client -> senders.execute(client::complete));
            }
        }
    }

    /** 先停止心跳和发送线程，再在领域锁之外结束连接。 */
    @EventListener(ContextClosedEvent.class)
    public void close() {
        List<Client> active;
        synchronized (tasks) {
            closing = true;
            active = clients.values().stream().flatMap(Set::stream).toList();
            clients.clear();
        }
        heartbeat.shutdownNow(); senders.shutdownNow();
        active.forEach(Client::complete);
    }

    /** 单连接串行发送，排队时覆盖旧快照以限制慢连接的内存占用。 */
    private final class Client {
        private final String owner;
        private final SseEmitter emitter;
        private final AtomicReference<Snapshot> pending = new AtomicReference<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean pulsePending = new AtomicBoolean();
        private volatile boolean removed;

        private Client(String owner, SseEmitter emitter) { this.owner = owner; this.emitter = emitter; }
        private void offer(Snapshot snapshot) {
            // 覆盖旧快照时保留尚未送达的历史失效信号，不能被下一次纯计时更新吞掉。
            pending.updateAndGet(previous -> previous != null && !previous.activityOnly() && snapshot.activityOnly()
                    ? snapshot.withActivityOnly(false) : snapshot);
            schedule();
        }
        private void pulse() { pulsePending.set(true); schedule(); }
        private void schedule() {
            if (removed || closing || !scheduled.compareAndSet(false, true)) return;
            try { senders.execute(this::send); }
            catch (RejectedExecutionException error) {
                scheduled.set(false);
                if (!closing) log.warn("任务快照发送队列不可用：{}", error.getClass().getSimpleName());
            }
        }
        private void send() {
            try {
                if (removed || closing) return;
                Snapshot snapshot = pending.getAndSet(null);
                if (snapshot != null) emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
                else if (pulsePending.getAndSet(false)) emitter.send(SseEmitter.event().name("heartbeat").data("ok"));
            } catch (IOException | RuntimeException error) {
                log.debug("任务事件客户端已断开：{}", error.getClass().getSimpleName());
                complete();
            } finally {
                scheduled.set(false);
                if (pending.get() != null || pulsePending.get()) schedule();
            }
        }
        private void remove() {
            removed = true; pending.set(null); pulsePending.set(false);
            var subscribers = clients.get(owner);
            if (subscribers != null) subscribers.remove(this);
        }
        private void complete() { remove(); emitter.complete(); }
    }
}
