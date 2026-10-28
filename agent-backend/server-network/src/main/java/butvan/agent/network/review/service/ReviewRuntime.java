package butvan.agent.network.review.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 独立复盘线程：不占用自动任务调度线程，也不在事务中等待模型。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewRuntime {
    private final ReviewCoachService reviews;
    private final ReviewDeliveryService deliveries;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "review-worker");
        thread.setDaemon(true);
        return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    private butvan.agent.agents.reset.ResetBarrier resetBarrier;

    /** 数据库迁移完成后再启动，避免启动早期扫描。 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker.scheduleWithFixedDelay(() -> {
            if (resetBarrier.blocked()) return;
            try { reviews.tick(); } catch (RuntimeException exception) {
                log.error("复盘工作项处理失败：{}", exception.getClass().getSimpleName());
            }
            try { deliveries.freezeReady(); } catch (RuntimeException exception) {
                log.error("复盘邮件冻结失败：{}", exception.getClass().getSimpleName());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
