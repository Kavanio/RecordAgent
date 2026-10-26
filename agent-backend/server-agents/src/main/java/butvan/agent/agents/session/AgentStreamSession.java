package butvan.agent.agents.session;

import butvan.agent.agents.agent.event.AgentStreamEvent;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单次 Agent 流式会话的运行状态。
 *
 * <p>会话持有生产者与 SSE 消费者之间的有界队列，并统一管理客户端断开后的取消动作。</p>
 */
public final class AgentStreamSession {

    private static final Logger log = LoggerFactory.getLogger(AgentStreamSession.class);

    private final String runId;

    /** 生产线程 finally 完成后发布；审批暂停或未落盘时结果为 null。 */
    private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();

    /**
     * 队列是生产者和消费者之间的缓冲区
     * 最多缓存 64 条事件。
     */
    private final BlockingQueue<AgentStreamEvent> queue = new LinkedBlockingQueue<>(64);

    /**
     * 客户端断开时设为 {@code true}，让生产者主动结束工作。
     */
    private final AtomicBoolean cancellationRequested = new AtomicBoolean(false);

    /** SSE 已断开时不再尝试向客户端发送终态。 */
    private final AtomicBoolean transportClosed = new AtomicBoolean(false);

    /** 每个运行只允许一个终态事件进入队列。 */
    private final AtomicBoolean terminalQueued = new AtomicBoolean(false);

    /**
     * 保存生产者虚拟线程。
     */
    private volatile Thread producerThread;

    /** AgentScope 原生中断动作，在 Agent 实例确定后绑定。 */
    private volatile Runnable cancellationAction;

    /** 原生中断动作最多执行一次，避免重复停止产生额外副作用。 */
    private final AtomicBoolean cancellationActionInvoked = new AtomicBoolean(false);

    public AgentStreamSession(String runId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId 不能为空");
        this.runId = runId;
    }

    /** 获取这次 Agent 运行的稳定标识。 */
    public String runId() {
        return runId;
    }

    /** 不受有界事件队列溢出影响的收尾结果；调用方仍须消费事件以支持审批。 */
    public CompletionStage<AgentTurnResult> completion() {
        return completion.minimalCompletionStage();
    }

    /** 仅由生产者在解注册之后调用；结果是已持久化正文而不是调用方拼接的 delta。 */
    public void settle(AgentTurnResult result) {
        completion.complete(result);
    }

    /**
     * 获取供 Controller 消费的流事件队列。
     *
     * @return 流事件队列
     */
    public BlockingQueue<AgentStreamEvent> queue() {
        return queue;
    }

    /**
     * 绑定本会话的 Agent 事件生产线程。
     *
     * @param producerThread 生产线程
     */
    public void bindProducer(Thread producerThread) {
        this.producerThread = producerThread;
        if (cancellationRequested.get() && cancellationActionInvoked.get()) {
            producerThread.interrupt();
        }
    }

    /** 绑定框架级取消动作，并处理“先取消、后绑定”竞态。 */
    public void bindCancellationAction(Runnable cancellationAction) {
        this.cancellationAction = cancellationAction;
        if (cancellationRequested.get()) triggerBoundCancellation();
    }

    /**
     * 判断当前会话是否已取消。
     *
     * @return 已取消时返回 {@code true}
     */
    public boolean isCancelled() {
        return cancellationRequested.get();
    }

    /**
     * 取消会话并中断仍在运行的生产线程。
     */
    public boolean requestCancellation() {
        boolean accepted = cancellationRequested.compareAndSet(false, true);
        if (accepted) triggerBoundCancellation();
        return accepted;
    }

    /** 标记 SSE 连接已断开，并取消后端运行。 */
    public void closeTransport() {
        transportClosed.set(true);
        requestCancellation();
    }

    /** 保留旧的非 HTTP 调用方语义：调用方不再消费事件时关闭运行。 */
    public void cancel() {
        closeTransport();
    }

    /** 非阻塞地放入唯一终态；队列塞满时优先保证终态可见。 */
    public boolean offerTerminal(AgentStreamEvent event) {
        if (!event.isTerminal() || transportClosed.get()) return false;
        if (!terminalQueued.compareAndSet(false, true)) return false;
        if (queue.offer(event)) return true;
        queue.clear();
        return queue.offer(event);
    }

    /** 只在 AgentScope 已绑定后中断运行，避免取消信号打断初始化阶段的 transcript I/O。 */
    private void triggerBoundCancellation() {
        Runnable action = cancellationAction;
        if (action == null) return;
        if (!cancellationActionInvoked.compareAndSet(false, true)) return;
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn("AgentScope 运行中断失败：runId={}", runId, exception);
        }
        Thread producer = producerThread;
        if (producer != null) producer.interrupt();
    }
}
