package butvan.agent.feishu.service;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.feishu.config.FeishuConfigData;
import com.lark.oapi.channel.model.NormalizedMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

import static butvan.agent.feishu.service.FeishuTaskStore.*;

/** 飞书受理、顺序执行与结果交付入口；不持有 AgentScope 编排或通用聊天记录实现。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeishuChatService implements AutoCloseable {
    private final FeishuAgentRunner runner;
    private final SessionCatalogService sessions;
    private final TranscriptService transcripts;
    private final ModelHolder modelHolder;
    private final CurrentUserProvider currentUser;
    private final Map<String, Running> running = new LinkedHashMap<>();
    private FeishuConfigData config;
    private FeishuTaskStore store;
    private FeishuDeliveryService delivery;
    private Clock clock;
    private LongSupplier nanoTime;
    private ExecutorService workers;
    private ScheduledExecutorService scheduler;
    private volatile boolean closed = true;
    private volatile boolean faulted;

    /** 生命周期先取得独占账本，再允许连接收件；持久化路径不接收用户消息中的任意路径。 */
    public synchronized void open(FeishuConfigData config, FeishuTextSender sender) throws IOException {
        requireClosed();
        String namespace = stableId(config.appId() + ":" + currentUser.currentUserId());
        open(config, sender, new FeishuTaskStore(Path.of(System.getProperty("user.home"),
                ".butvan-agent", "channels", "feishu", namespace)), Clock.systemUTC(), System::nanoTime, true);
    }

    /** 内部时钟和存储 seam 用于确定性的断网、重启与心跳测试。 */
    synchronized void open(FeishuConfigData config, FeishuTextSender sender, FeishuTaskStore store,
                           Clock clock, LongSupplier nanoTime, boolean automaticTicks) {
        requireClosed();
        this.config = config; this.store = store; this.clock = clock; this.nanoTime = nanoTime;
        delivery = new FeishuDeliveryService(store, sender, clock);
        workers = Executors.newFixedThreadPool(config.maxConcurrentRuns(),
                Thread.ofVirtual().name("feishu-task-", 0).factory());
        closed = false; faulted = false;
        try {
            recover();
            if (automaticTicks) {
                scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("feishu-tick").factory());
                scheduler.scheduleWithFixedDelay(this::safeTick, 0, 250, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
    }

    /** 长连接回调只进行受理落盘和有界调度，不等待模型或平台发送。 */
    public synchronized void handle(NormalizedMessage message) {
        if (closed || faulted || message == null) return;
        // 首版只允许一个本人 open_id 的私聊，群聊即使 SDK 放行也不会触发任何回执或工具。
        if (!"p2p".equals(message.getChatType()) || !config.allowedSenderIds().contains(message.getSenderId())) return;
        if (message.getMessageId() == null || message.getChatId() == null) return;
        String conversation = stableId(config.appId() + ":" + currentUser.currentUserId() + ":"
                + message.getChatId() + ":" + Objects.toString(message.getThreadId(), "") + ":" + message.getSenderId());
        String taskId = stableId(config.appId() + ":" + currentUser.currentUserId() + ":" + message.getMessageId());
        String runId = UUID.randomUUID().toString();
        Task task = Task.builder().id(taskId).conversation(conversation).chatId(message.getChatId())
                .replyTo(message.getMessageId()).senderId(message.getSenderId()).runId(runId).turnId(runId)
                .input(message.getContent()).state(Execution.QUEUED).acceptedAt(clock.millis()).stage("待处理").build();
        try {
            if (store.task(taskId) != null) return;
            String text = message.getContent();
            boolean command = text != null && isCommand(text.strip());
            String rejection = !"text".equalsIgnoreCase(message.getRawContentType()) ? "目前只支持文本消息。"
                    : text == null || text.isBlank() ? "消息内容不能为空。"
                    : text.length() > 20_000 ? "消息内容不能超过 20000 个字符。"
                    : !command && !modelHolder.isInitialized() ? "模型尚未配置，请先在桌面端完成模型初始化。" : null;
            if (command || rejection != null) {
                // 控制与校验反馈不占等待队列，但同样持久化去重。
                task = task.toBuilder().state(Execution.SUCCEEDED).stage("控制反馈").build();
                if (store.accept(task, config.maxQueuedPerSession(), clock.millis()) < 0) return;
                finish(task.id(), Execution.SUCCEEDED, command && rejection == null ? control(task, text.strip()) : rejection);
            } else {
                int ahead = store.accept(task, config.maxQueuedPerSession(), clock.millis());
                if (ahead < 0) return;
                log.info("飞书消息已持久化受理：taskId={}, runId={}, ahead={}, inputLength={}",
                        task.id(), task.runId(), ahead, text.length());
                if (ahead > 0) store.update(task.id(), t -> t.toBuilder().stage("等待执行").build());
                String receipt = ahead == 0 ? "已收到，正在处理…" : "已收到，前面还有 " + ahead + " 条消息，请稍候。";
                final String acceptedId = task.id();
                delivery.notice(task, "receipt", receipt, true, () -> active(acceptedId));
            }
            delivery.pump();
            dispatch();
        } catch (RuntimeException exception) {
            if (!store.healthy()) freeze();
            log.warn("飞书消息受理失败：taskId={}, errorType={}", taskId, exception.getClass().getSimpleName());
            if (store.healthy()) {
                delivery.notice(task, "rejected", "当前待处理消息较多或状态不可用，请稍后重试。", true, () -> true);
                delivery.pump();
            }
        }
    }

    private boolean isCommand(String text) {
        return Set.of("/stop", "/clear", "/new").contains(text)
                || text.matches("/(retry|resend) [a-f0-9-]{36}");
    }

    private String control(Task command, String text) {
        List<Task> tasks = store.snapshot().tasks().stream().filter(t -> t.conversation().equals(command.conversation())
                && !t.id().equals(command.id())).toList();
        if (text.equals("/stop")) {
            Task active = tasks.stream().filter(t -> t.state() == Execution.RUNNING || t.state() == Execution.STOPPING).findFirst().orElse(null);
            if (active == null) return "当前没有正在执行的任务。";
            Running execution = running.get(active.id());
            if (execution != null) execution.control.stop("user");
            store.update(active.id(), t -> t.toBuilder().state(Execution.STOPPING).build());
            delivery.discardNotices(active.id());
            return "正在停止当前任务；等待队列保留，如需清空请发送 /clear。";
        }
        if (text.equals("/clear")) {
            int count = 0;
            for (Task task : tasks) if (task.state() == Execution.QUEUED) {
                finish(task.id(), Execution.CLEARED, "本条等待任务已清空，未执行。"); count++;
            }
            return "已清空 " + count + " 条等待消息，正在执行的任务不受影响。";
        }
        if (text.equals("/new")) {
            if (tasks.stream().anyMatch(t -> t.state().active())) return "还有任务正在执行或排队，请先 /stop 和 /clear，再新建对话。";
            String session = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "飞书对话")).id();
            store.bind(command.conversation(), session);
            return "已建立新对话，之后的消息将使用新的上下文。";
        }
        String id = text.substring(text.indexOf(' ') + 1);
        Task target = store.task(id);
        if (target == null || !target.conversation().equals(command.conversation())) return "未找到属于当前聊天的任务。";
        boolean unknown = target.parts().stream().anyMatch(p -> p.status() == Delivery.UNKNOWN);
        if (unknown && text.startsWith("/retry")) return "该回复可能已经送达。补发可能重复；确认后请发送 /resend " + id;
        if (target.parts().stream().noneMatch(p -> p.status() == Delivery.FAILED || p.status() == Delivery.UNKNOWN)) return "该任务没有需要补发的失败回复。";
        delivery.retry(id, text.startsWith("/resend"));
        return "已安排补发保存的回复，不会重新执行 Agent 或工具。";
    }

    /** 定时器只读运行状态并调度，异常必须被捕获，不能让整个周期任务永久停止。 */
    private void safeTick() {
        try { tick(); } catch (RuntimeException exception) {
            log.error("飞书后台调度失败：errorType={}", exception.getClass().getSimpleName());
            if (store != null && !store.healthy()) freeze();
        }
    }

    synchronized void tick() {
        if (closed || faulted) return;
        long now = nanoTime.getAsLong();
        for (Map.Entry<String, Running> entry : running.entrySet()) {
            Task task = store.task(entry.getKey());
            Running execution = entry.getValue();
            if (execution.control.stopping()) {
                if (task.state() == Execution.RUNNING) {
                    store.update(task.id(), t -> t.toBuilder().state(Execution.STOPPING).build());
                    delivery.discardNotices(task.id());
                    delivery.notice(task, "stopping", "任务正在停止，尚未确认收尾完成。", true,
                            () -> store.task(task.id()).state() == Execution.STOPPING);
                }
                continue;
            }
            if (task.state() == Execution.RUNNING && now - execution.nextHeartbeat >= 0) {
                execution.nextHeartbeat = now + TimeUnit.SECONDS.toNanos(config.heartbeatSeconds());
                long minutes = Math.max(1, TimeUnit.NANOSECONDS.toMinutes(now - execution.started));
                store.update(task.id(), t -> t.toBuilder().nextHeartbeatAt(clock.millis() + config.heartbeatSeconds() * 1000L)
                        .stage(execution.control.stage()).build());
                delivery.notice(task, "heartbeat", "任务仍在处理中，已用时约 " + minutes + " 分钟；"
                        + execution.control.stage() + "，请稍候。", false,
                        () -> store.task(task.id()).state() == Execution.RUNNING && !execution.control.stopping());
            }
        }
        dispatch();
        delivery.pump();
    }

    private void dispatch() {
        if (closed || faulted) return;
        Set<String> occupied = new HashSet<>();
        for (String id : running.keySet()) occupied.add(store.task(id).conversation());
        for (Task task : store.snapshot().tasks()) {
            if (task.state() != Execution.QUEUED) continue;
            if (clock.millis() - task.acceptedAt() >= config.queueTimeoutSeconds() * 1000L) {
                finish(task.id(), Execution.EXPIRED, "等待时间过长，本条尚未执行，请重新发送。"); continue;
            }
            if (running.size() >= config.maxConcurrentRuns() || occupied.contains(task.conversation())) continue;
            try {
                String session = resolveSession(task.conversation());
                Running execution = new Running(new FeishuAgentRunner.Control(), nanoTime.getAsLong(), config.heartbeatSeconds());
                store.update(task.id(), t -> t.toBuilder().state(Execution.RUNNING).sessionId(session)
                        .startedAt(clock.millis()).nextHeartbeatAt(clock.millis() + config.heartbeatSeconds() * 1000L).build());
                running.put(task.id(), execution); occupied.add(task.conversation());
                if ("等待执行".equals(task.stage())) delivery.notice(task, "started", "已开始处理你的这条消息。", true, () -> active(task.id()));
                Task started = store.task(task.id());
                log.info("飞书任务开始：taskId={}, runId={}, queueMs={}", task.id(), task.runId(),
                        Math.max(0, clock.millis() - task.acceptedAt()));
                workers.execute(() -> execute(started, execution));
            } catch (RuntimeException exception) {
                running.remove(task.id());
                log.warn("飞书任务启动失败：taskId={}, errorType={}", task.id(), exception.getClass().getSimpleName());
                finish(task.id(), Execution.FAILED, "任务未能启动，请检查桌面端会话状态后重试。");
            }
        }
    }

    private String resolveSession(String conversation) {
        String existing = store.snapshot().bindings().get(conversation);
        if (existing != null) {
            try { sessions.requireActive(existing); return existing; }
            catch (IllegalArgumentException exception) { log.info("飞书绑定会话已失效，将建立新会话"); }
        }
        String created = sessions.create(new CreateSessionRequest(SessionKind.GENERAL, "飞书对话")).id();
        store.bind(conversation, created);
        return created;
    }

    private void execute(Task task, Running execution) {
        try {
            FeishuAgentRunner.Reply reply = runner.run(task.sessionId(), task.runId(), task.turnId(), task.input(),
                    Duration.ofSeconds(config.executionTimeoutSeconds()), execution.control);
            synchronized (this) {
                Execution state = reply.result() == null
                        ? "user".equals(reply.stopReason()) ? Execution.CANCELLED
                        : "shutdown".equals(reply.stopReason()) ? Execution.INTERRUPTED : Execution.FAILED
                        : switch (reply.result().status()) {
                    case COMPLETED -> Execution.SUCCEEDED;
                    case FAILED -> Execution.FAILED;
                    case CANCELLED -> "timeout".equals(reply.stopReason()) ? Execution.TIMED_OUT : Execution.CANCELLED;
                };
                finish(task.id(), state, reply.text());
            }
        } catch (RuntimeException exception) {
            log.error("飞书任务收尾失败：taskId={}, errorType={}", task.id(), exception.getClass().getSimpleName());
            freeze();
        } finally {
            synchronized (this) {
                running.remove(task.id());
                if (!closed && !faulted) { dispatch(); delivery.pump(); }
            }
        }
    }

    private void finish(String taskId, Execution state, String text) {
        List<String> chunks = FeishuTextChunks.split(text);
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) parts.add(Part.builder().id(stableId(taskId + ":part:" + i))
                .text(chunks.get(i)).status(Delivery.PENDING).build());
        store.update(taskId, task -> task.toBuilder().state(state).input(null).finishedAt(clock.millis()).parts(parts).build());
        delivery.discardNotices(taskId);
        log.info("飞书任务已保存终态：taskId={}, state={}, outputLength={}, parts={}",
                taskId, state, text == null ? 0 : text.length(), parts.size());
    }

    private boolean active(String id) { Task task = store.task(id); return task != null && task.state().active(); }

    private void recover() {
        for (Task task : store.snapshot().tasks()) {
            if (task.state().active()) {
                TranscriptMessageDto saved = task.sessionId() == null ? null : transcripts.list(task.sessionId()).stream()
                        .filter(m -> task.turnId().equals(m.turnId()) && m.role() == TranscriptMessageDto.MessageRole.ASSISTANT)
                        .findFirst().orElse(null);
                if (saved != null && saved.status() == TranscriptMessageDto.MessageStatus.COMPLETED) {
                    finish(task.id(), Execution.SUCCEEDED, saved.content());
                } else finish(task.id(), Execution.INTERRUPTED, "服务已重启，本条任务已中断且不会自动重新执行。请检查实际结果后决定是否重新发起。");
            } else if (task.parts().stream().anyMatch(p -> p.status() == Delivery.SENDING)) {
                store.update(task.id(), t -> t.toBuilder().parts(t.parts().stream().map(part -> part.status() == Delivery.SENDING
                        ? part.toBuilder().status(Delivery.UNKNOWN).errorCode("restart_during_send").build() : part).toList()).build());
                delivery.notice(task, "recovery", "服务曾在发送回复时中断，发送状态不确定，完整结果已保留。补发可能重复；确认后请发送 /resend "
                        + task.id(), true, () -> true);
            }
        }
    }

    private void requireClosed() {
        if (!closed || (workers != null && !workers.isTerminated()) || (delivery != null && !delivery.terminated())) {
            throw new IllegalStateException("飞书渠道仍在运行或收尾，禁止并行启动");
        }
    }

    private synchronized void freeze() {
        if (faulted) return;
        faulted = true;
        running.values().forEach(execution -> execution.control.stop("storage_failure"));
        log.error("飞书渠道已停止受理及投递，请修复本地状态后重启");
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (scheduler != null) scheduler.shutdownNow();
            running.values().forEach(execution -> execution.control.stop("shutdown"));
            workers.shutdown();
        }
        try {
            if (!workers.awaitTermination(15, TimeUnit.SECONDS)) workers.shutdownNow();
        } catch (InterruptedException exception) { workers.shutdownNow(); Thread.currentThread().interrupt(); }
        delivery.close();
        if (workers.isTerminated() && delivery.terminated()) {
            try { store.close(); } catch (IOException exception) { log.warn("飞书账本锁释放失败"); }
        } else log.error("仍有任务或发送未确认结束，保留渠道独占锁直到进程退出，禁止并行启动");
    }

    private static final class Running {
        final FeishuAgentRunner.Control control;
        final long started;
        long nextHeartbeat;
        Running(FeishuAgentRunner.Control control, long started, int intervalSeconds) {
            this.control = control; this.started = started;
            this.nextHeartbeat = started + TimeUnit.SECONDS.toNanos(intervalSeconds);
        }
    }
}
