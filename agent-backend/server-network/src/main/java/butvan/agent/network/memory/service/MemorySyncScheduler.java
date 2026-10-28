package butvan.agent.network.memory.service;

import butvan.agent.agents.reset.ResetBlockedException;
import butvan.agent.network.memory.client.MemoryWriteGateway;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.*;

/** 独立单消费者；未配置或未授权时不读取聊天，不占用自动任务的业务调度器。 */
@Slf4j
@Component
public class MemorySyncScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor;
    @Autowired public MemorySyncScheduler(MemorySyncService sync, MemoryWriteGateway writer) { this(sync, writer, Duration.ofSeconds(2)); }
    /** 短间隔仅供隔离测试；生产固定两秒，每次最多五项，无并行堆积。 */
    public MemorySyncScheduler(MemorySyncService sync, MemoryWriteGateway writer, Duration delay) {
        if (delay == null || delay.compareTo(Duration.ofMillis(10)) < 0 || delay.compareTo(Duration.ofSeconds(2)) > 0)
            throw new IllegalArgumentException("记忆调度间隔无效");
        executor = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon(true).name("memory-sync-worker").unstarted(task));
        executor.scheduleWithFixedDelay(() -> {
            if (writer.recipientFingerprint() == null || writer.recipientFingerprint().isBlank()) return;
            try { sync.runOnce(5); }
            catch (ResetBlockedException blocked) { log.debug("记忆消费因重置保护暂停"); }
            catch (RuntimeException failure) { log.warn("记忆后台消费未完成：errorType={}", failure.getClass().getSimpleName()); }
        }, delay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }
    /** 中断由原有 HTTP／租约传播，真实生产者停稳前不伪造释放保护。 */
    @PreDestroy public void close() { executor.shutdownNow(); }
}
