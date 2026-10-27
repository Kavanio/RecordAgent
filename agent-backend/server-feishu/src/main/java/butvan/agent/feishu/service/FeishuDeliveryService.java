package butvan.agent.feishu.service;

import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static butvan.agent.feishu.service.FeishuTaskStore.*;

/** 有界出站调度。相同聊天同时只发一条，发送前落盘 SENDING，未知送达绝不自动重发；账本只保存正文原文。 */
@Slf4j
public final class FeishuDeliveryService implements AutoCloseable {
    private static final int MAX_NOTICES = 128;
    private final FeishuTaskStore store;
    private final FeishuMessageSender sender;
    private final Clock clock;
    private final ExecutorService io = Executors.newFixedThreadPool(2,
            Thread.ofVirtual().name("feishu-send-", 0).factory());
    private final Map<String, Notice> notices = new LinkedHashMap<>();
    private final Set<String> inFlightChats = new HashSet<>();
    private volatile boolean closed;

    public FeishuDeliveryService(FeishuTaskStore store, FeishuMessageSender sender, Clock clock) {
        this.store = store; this.sender = sender; this.clock = clock;
    }

    /** 回执优先于最终结果，最终结果优先于心跳；短暂通知不积压重试。 */
    public synchronized void notice(Task task, String kind, String text, boolean urgent, BooleanSupplier valid) {
        if (closed || notices.size() >= MAX_NOTICES) return;
        String key = task.id() + ":" + kind;
        // 同一类待发提醒在本地合并，但每次实际提醒属于新消息，不能复用平台一小时去重 uuid。
        String deliveryId = kind.equals("heartbeat") || kind.equals("delivery-error")
                ? UUID.randomUUID().toString() : stableId(key);
        notices.put(key, new Notice(task.chatId(), task.replyTo(), deliveryId, text,
                clock.millis() + 30_000, urgent, valid, task.id(), kind, clock.millis()));
    }

    /** 丢弃某任务所有尚未发送的回执／进度；已在途平台请求无法保证撤回。 */
    public synchronized void discardNotices(String taskId) {
        notices.keySet().removeIf(key -> key.startsWith(taskId + ":"));
    }

    /** 每次最多占用两个聊天发送槽位，不把网络请求放在调度线程执行。 */
    public synchronized void pump() {
        if (closed || !store.healthy()) return;
        while (inFlightChats.size() < 2) {
            Work work = next();
            if (work == null) break;
            inFlightChats.add(work.chatId());
            try {
                io.execute(() -> send(work));
            } catch (RejectedExecutionException exception) {
                inFlightChats.remove(work.chatId());
                // 若已领取最终段则保持 SENDING，下次启动按 UNKNOWN 恢复，不能假定尚未送达。
                log.warn("飞书发送执行器已停止，保留待核对投递状态");
                return;
            }
        }
    }

    private Work next() {
        Work urgent = nextNotice(true);
        if (urgent != null) return urgent;
        Set<String> blockedConversations = new HashSet<>();
        long now = clock.millis();
        // 控制命令和校验反馈不能被旧回复的 UNKNOWN 状态挡住，否则无法通过聊天解除投递暂停。
        List<Task> ordered = store.snapshot().tasks().stream()
                .sorted(Comparator.comparing(task -> !"控制反馈".equals(task.stage()))).toList();
        for (Task task : ordered) {
            if (task.parts().isEmpty()) continue;
            if (blockedConversations.contains(task.conversation())) continue;
            for (int i = 0; i < task.parts().size(); i++) {
                Part part = task.parts().get(i);
                if (part.status() == Delivery.SENT) continue;
                blockedConversations.add(task.conversation());
                if (inFlightChats.contains(task.chatId()) || part.status() == Delivery.SENDING
                        || part.status() == Delivery.FAILED || part.status() == Delivery.UNKNOWN
                        || part.retryAt() > now) break;
                if (part.firstAttemptAt() > 0 && now - part.firstAttemptAt() >= 50 * 60_000L) {
                    setPart(task.id(), i, part.toBuilder().status(Delivery.UNKNOWN).errorCode("dedup_window_expired").build());
                    break;
                }
                Part claimed = part.toBuilder().status(Delivery.SENDING).attempts(part.attempts() + 1)
                        .firstAttemptAt(part.firstAttemptAt() == 0 ? now : part.firstAttemptAt()).build();
                setPart(task.id(), i, claimed);
                return new Work(task.chatId(), task.replyTo(), claimed.id(), claimed.text(), task.id(), i, null);
            }
        }
        return nextNotice(false);
    }

    private Work nextNotice(boolean urgent) {
        Iterator<Notice> iterator = notices.values().iterator();
        while (iterator.hasNext()) {
            Notice notice = iterator.next();
            if (notice.expiresAt() <= clock.millis() || !notice.valid().getAsBoolean()) {
                iterator.remove(); continue;
            }
            if (notice.urgent() != urgent || inFlightChats.contains(notice.chatId())) continue;
            iterator.remove();
            return new Work(notice.chatId(), notice.replyTo(), notice.id(), notice.text(), null, -1, notice);
        }
        return null;
    }

    private void send(Work work) {
        try {
            if (closed || !store.healthy()) return;
            if (work.notice() != null && (work.notice().expiresAt() <= clock.millis()
                    || !work.notice().valid().getAsBoolean())) return;
            long started = System.nanoTime();
            // 提醒与控制反馈是普通文本；任务最终回复按其 Markdown 原样渲染。
            FeishuMessageSender.Attempt attempt = sender.send(work.chatId(), work.replyTo(), work.id(), work.text(),
                    work.notice() != null ? FeishuMessageSender.Format.TEXT : FeishuMessageSender.Format.MARKDOWN);
            long costMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (work.taskId() == null) {
                log.info("飞书状态投递：taskId={}, kind={}, status={}, queueMs={}, costMs={}",
                        work.notice().taskId(), work.notice().kind(), attempt.status(),
                        Math.max(0, clock.millis() - work.notice().queuedAt()), costMillis);
                if (attempt.status() != FeishuMessageSender.Status.SENT) {
                    log.warn("飞书短暂状态通知未送达：status={}, code={}", attempt.status(), attempt.code());
                }
                return;
            }
            Task task = store.task(work.taskId());
            Part part = task.parts().get(work.partIndex());
            Part updated;
            switch (attempt.status()) {
                case SENT -> updated = part.toBuilder().status(Delivery.SENT).messageId(attempt.messageId()).errorCode(null).build();
                case RETRYABLE -> updated = part.toBuilder()
                        .status(part.attempts() >= 4 ? Delivery.FAILED : Delivery.RETRY_WAIT)
                        .retryAt(clock.millis() + Math.max(attempt.retryAfterMillis(), (long) Math.pow(3, part.attempts() - 1) * 1000))
                        .errorCode(attempt.code()).build();
                case FAILED -> updated = part.toBuilder().status(Delivery.FAILED).errorCode(attempt.code()).build();
                default -> updated = part.toBuilder().status(Delivery.UNKNOWN).errorCode(attempt.code()).build();
            }
            setPart(task.id(), work.partIndex(), updated);
            log.info("飞书正文投递：taskId={}, part={}, status={}, attempt={}, costMs={}",
                    task.id(), work.partIndex() + 1, updated.status(), updated.attempts(), costMillis);
            if (updated.status() == Delivery.UNKNOWN || updated.status() == Delivery.FAILED) {
                log.warn("飞书最终回复投递暂停：taskId={}, part={}, status={}, code={}",
                        task.id(), work.partIndex(), updated.status(), updated.errorCode());
                String text = updated.status() == Delivery.UNKNOWN
                        ? "回复发送状态不确定，已保留完整结果。补发可能重复；如确认补发，请发送 /resend " + task.id()
                        : "回复发送失败，已保留完整结果。修复权限或网络后，可发送 /retry " + task.id();
                notice(task, "delivery-error", text, true, () -> true);
            }
        } catch (RuntimeException exception) {
            // 若保存发送确认失败，账本会冻结；SENDING 留待重启核对，不在此覆盖成可重试。
            log.error("飞书投递处理失败：errorType={}", exception.getClass().getSimpleName());
        } finally {
            synchronized (this) { inFlightChats.remove(work.chatId()); }
        }
    }

    private void setPart(String taskId, int index, Part part) {
        store.update(taskId, task -> {
            List<Part> parts = new ArrayList<>(task.parts()); parts.set(index, part);
            return task.toBuilder().parts(parts).build();
        });
    }

    /** 人工重试只处理失败段；UNKNOWN 必须使用明确接受重复风险的补发命令。 */
    public void retry(String taskId, boolean allowUnknown) {
        log.info("飞书人工补发：taskId={}, allowUnknown={}", taskId, allowUnknown);
        store.update(taskId, task -> {
            List<Part> parts = task.parts().stream().map(part -> {
                if (part.status() == Delivery.FAILED || (allowUnknown && part.status() == Delivery.UNKNOWN)) {
                    // 超过幂等窗口的人工补发是新的投递；用户已明确选择，仍不重执行 Agent。
                    return part.toBuilder().id(UUID.randomUUID().toString()).status(Delivery.PENDING)
                            .attempts(0).firstAttemptAt(0).retryAt(0).errorCode(null).build();
                }
                return part;
            }).toList();
            return task.toBuilder().parts(parts).build();
        });
    }

    @Override
    public void close() {
        closed = true;
        synchronized (this) { notices.clear(); }
        io.shutdown();
        try {
            if (!io.awaitTermination(20, TimeUnit.SECONDS)) io.shutdownNow();
        } catch (InterruptedException exception) {
            io.shutdownNow(); Thread.currentThread().interrupt();
        }
    }

    /** 真正退出后调用方才可释放状态目录锁。 */
    public boolean terminated() { return io.isTerminated(); }

    private record Notice(String chatId, String replyTo, String id, String text, long expiresAt,
                          boolean urgent, BooleanSupplier valid, String taskId, String kind, long queuedAt) { }
    private record Work(String chatId, String replyTo, String id, String text, String taskId,
                        int partIndex, Notice notice) { }
}
