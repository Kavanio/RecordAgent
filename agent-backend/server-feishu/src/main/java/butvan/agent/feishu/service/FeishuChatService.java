package butvan.agent.feishu.service;

import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.agent.AgentImages;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.model.ModelHolder;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.feishu.config.FeishuConfigData;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.channel.model.ResourceDescriptor;
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
public class FeishuChatService implements AutoCloseable, butvan.agent.agents.review.PersonalDeliveryPort {
    private final FeishuAgentRunner runner;
    private final SessionCatalogService sessions;
    private final TranscriptService transcripts;
    private final ModelHolder modelHolder;
    private final CurrentUserProvider currentUser;
    private final Map<String, Running> running = new LinkedHashMap<>();
    /** 待执行图片的平台键；只在内存保留，重启后对应任务直接标记中断，不重下载也不重执行。 */
    private final Map<String, String> pendingImages = new ConcurrentHashMap<>();
    private FeishuMessageSender personalSender;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<butvan.agent.agents.review.LifeReviewReplyPort> reviewReplies;
    private FeishuImageFetcher imageFetcher;
    private FeishuConfigData config;
    private FeishuTaskStore store;
    private FeishuDeliveryService delivery;
    private Clock clock;
    private LongSupplier nanoTime;
    private ExecutorService workers;
    private ScheduledExecutorService scheduler;
    private volatile boolean closed = true;
    private volatile boolean faulted;
    @org.springframework.beans.factory.annotation.Autowired
    private butvan.agent.agents.reset.ResetBarrier resetBarrier;
    @org.springframework.beans.factory.annotation.Autowired
    private butvan.agent.agents.storage.AgentStorageProperties storageProperties;

    /** 生命周期先取得独占账本，再允许连接收件；持久化路径不接收用户消息中的任意路径。 */
    public synchronized void open(FeishuConfigData config, FeishuMessageSender sender,
                                  FeishuImageFetcher imageFetcher) throws IOException {
        requireClosed();
        String namespace = stableId(config.appId() + ":" + currentUser.currentUserId());
        open(config, sender, imageFetcher, new FeishuTaskStore(storageProperties.getFeishuDirectory().resolve(namespace)), Clock.systemUTC(), System::nanoTime, true);
    }

    /** 内部时钟和存储 seam 用于确定性的断网、重启与心跳测试。 */
    synchronized void open(FeishuConfigData config, FeishuMessageSender sender, FeishuImageFetcher imageFetcher,
                           FeishuTaskStore store, Clock clock, LongSupplier nanoTime, boolean automaticTicks) {
        requireClosed();
        this.personalSender = sender;
        this.config = config; this.store = store; this.clock = clock; this.nanoTime = nanoTime;
        this.imageFetcher = imageFetcher;
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
        if (closed || faulted || message == null || (resetBarrier != null && resetBarrier.blocked())) return;
        // 首版只允许一个本人 open_id 的私聊，群聊即使 SDK 放行也不会触发任何回执或工具。
        if (!"p2p".equals(message.getChatType()) || !config.allowedSenderIds().contains(message.getSenderId())) return;
        if (message.getMessageId() == null || message.getChatId() == null) return;
        if (!store.acceptsMessageTime(message.getCreateTime())) return;
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
            String imageKey = imageKey(message);
            String rejection = rejection(message, text, command, imageKey);
            if (imageKey != null) {
                // 图片消息没有正文；给模型和 transcript 一个可读说明，图片本身在执行线程里按需下载。
                task = task.toBuilder().input(AgentImages.IMAGE_ONLY_PROMPT).build();
            }
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
                if (imageKey != null) pendingImages.put(task.id(), imageKey);
                String receipt = (ahead == 0 ? "已收到，正在处理…" : "已收到，前面还有 " + ahead + " 条消息，请稍候。")
                        + pendingDeliveryHint(task.conversation());
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

    /**
     * 同会话存在待核对的回复时，回执里直接给出可解除卡住的命令。
     * UNKNOWN／FAILED 会按设计暂停该会话后续回复，只在日志里留线索会让用户以为渠道死掉。
     */
    private String pendingDeliveryHint(String conversation) {
        return store.snapshot().tasks().stream()
                .filter(task -> task.conversation().equals(conversation))
                .filter(task -> task.parts().stream().anyMatch(part -> part.status() == Delivery.UNKNOWN
                        || part.status() == Delivery.FAILED))
                .max(Comparator.comparing(Task::acceptedAt))
                .map(task -> task.parts().stream().anyMatch(part -> part.status() == Delivery.UNKNOWN)
                        ? "\n（此前有回复发送状态不确定，确认已收到可发送 /ack " + task.id() + "，需要补发则发送 /resend " + task.id() + "）"
                        : "\n（此前有回复发送失败；可发送 /retry " + task.id() + " 补发）")
                .orElse("");
    }

    /**
     * 受理前的统一校验。拒绝原因必须是用户照者能改的中文说明；图片先看模型能力再看格式，
     * 真正的字节校验放到执行线程，避免在长连接回调里做网络和解析。
     */
    private String rejection(NormalizedMessage message, String text, boolean command, String imageKey) {
        if ("post".equalsIgnoreCase(message.getRawContentType())) {
            return "暂不支持图文混排消息，请将文字和图片分开发送（本条未提交给模型）";
        }
        if ("image".equalsIgnoreCase(message.getRawContentType()) && imageKey == null) {
            return "未取得图片资源，请重新发送图片";
        }
        if (imageKey != null) {
            if (!modelHolder.isInitialized()) return "模型尚未配置，请先在桌面端完成模型初始化。";
            return modelHolder.supportsVision() ? null
                    : "当前模型没有标注图片识别能力，请先在桌面端「模型配置」里开启后再发送图片。";
        }
        if (!"text".equalsIgnoreCase(message.getRawContentType())) return "目前只支持文本与图片消息。";
        if (text == null || text.isBlank()) return "消息内容不能为空。";
        if (text.length() > 20_000) return "消息内容不能超过 20000 个字符。";
        return !command && !modelHolder.isInitialized() ? "模型尚未配置，请先在桌面端完成模型初始化。" : null;
    }

    /** 取图片消息的资源键；不下载任何内容，非图片消息返回 null。 */
    private static String imageKey(NormalizedMessage message) {
        if (!"image".equalsIgnoreCase(message.getRawContentType())
                || message.getResources() == null || message.getResources().isEmpty()) return null;
        return message.getResources().stream()
                .filter(resource -> resource.getFileKey() != null && !resource.getFileKey().isBlank())
                .filter(resource -> "image".equalsIgnoreCase(resource.getType()))
                .map(ResourceDescriptor::getFileKey).findFirst().orElse(null);
    }

    /** 只暴露脱敏本人身份指纹；渠道未打开或故障时不允许授权主动发送。 */
    @Override
    public synchronized butvan.agent.agents.review.PersonalDeliveryPort.Target target() {
        boolean known = config != null && config.isReady();
        boolean ready = known && !closed && !faulted;
        return new butvan.agent.agents.review.PersonalDeliveryPort.Target(ready,
                known ? stableId(config.appId() + ":" + config.allowedSenderIds().getFirst()) : "",
                ready ? "已配置的本人飞书私聊" : "本人飞书暂不可用或未连接");
    }

    /** 调用方已持有重置租约并记录意图；网络等待不占受理锁。 */
    @Override
    public butvan.agent.agents.review.PersonalDeliveryPort.Attempt send(String expected, String deliveryId, String text) {
        FeishuMessageSender sender;
        String recipient;
        synchronized (this) {
            var target = target();
            if (!target.available() || !target.fingerprint().equals(expected)
                    || (resetBarrier != null && resetBarrier.blocked()))
                return new butvan.agent.agents.review.PersonalDeliveryPort.Attempt("FAILED", "");
            sender = personalSender;
            recipient = config.allowedSenderIds().getFirst();
        }
        var result = sender.sendToOwner(recipient, deliveryId, text);
        return new butvan.agent.agents.review.PersonalDeliveryPort.Attempt(
                result.status() == FeishuMessageSender.Status.SENT ? "SENT"
                        : result.status() == FeishuMessageSender.Status.UNKNOWN ? "UNKNOWN" : "FAILED", result.messageId());
    }

    private boolean isCommand(String text) {
        return text.matches("(?s)/review [a-f0-9-]{36} .{1,1000}")
                || Set.of("/stop", "/clear", "/new").contains(text)
                || text.matches("/(retry|resend|ack) [a-f0-9-]{36}");
    }

    private String control(Task command, String text) {
        if (text.startsWith("/review ")) {
            if (reviewReplies == null || reviewReplies.getIfUnique() == null) return "当前后端不支持生活复盘补充，请到桌面查看。";
            String[] parts = text.split(" ", 3);
            return reviewReplies.getIfUnique().reply(currentUser.currentUserId(), parts[1], command.id(), parts[2]);
        }
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
        if (text.startsWith("/ack")) {
            if (!unknown && target.parts().stream().noneMatch(p -> p.status() == Delivery.FAILED)) {
                return "该任务没有需要确认的待定投递。";
            }
            // 只在用户亲自确认已经看到内容时使用：不重发、不重执行，只解除该会话的投递暂停。
            delivery.acknowledge(id);
            return "已确认该回复无需补发，该会话已恢复自动发送。";
        }
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
        if (closed || faulted || (resetBarrier != null && resetBarrier.blocked())) return;
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
        if (closed || faulted || (resetBarrier != null && resetBarrier.blocked())) return;
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
            List<AgentImageAttachment> images = List.of();
            String imageKey = pendingImages.remove(task.id());
            if (imageKey != null) {
                if (!modelHolder.supportsVision()) {
                    finish(task.id(), Execution.FAILED, "当前模型未标注图片识别能力，请切换到视觉模型后重发图片");
                    return;
                }
                try {
                    byte[] data = imageFetcher.fetch(task.replyTo(), imageKey);
                    images = List.of(AgentImages.parse(data));
                    log.info("飞书图片已读取：taskId={}, mediaType={}, bytes={}", task.id(), images.getFirst().mediaType(), data.length);
                } catch (RuntimeException exception) {
                    // 不重试下载：平台图片键有时效，反复重试只会拖延用户反馈。
                    log.warn("飞书图片读取失败：taskId={}, errorType={}", task.id(), exception.getClass().getSimpleName());
                    finish(task.id(), Execution.FAILED,
                            "图片读取失败：仅支持 10 MiB 以内的 PNG、JPEG、WebP 或 GIF，请调整后重新发送。");
                    return;
                }
            }
            FeishuAgentRunner.Reply reply = runner.run(task.sessionId(), task.runId(), task.turnId(), task.input(), images,
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
                if (!closed && !faulted && (resetBarrier == null || !resetBarrier.blocked())) { dispatch(); delivery.pump(); }
            }
        }
    }

    private void finish(String taskId, Execution state, String text) {
        pendingImages.remove(taskId);
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
