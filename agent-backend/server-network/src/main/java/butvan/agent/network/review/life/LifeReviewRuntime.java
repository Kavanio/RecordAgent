package butvan.agent.network.review.life;

import butvan.agent.agents.reset.ResetBarrier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.concurrent.*;

/** 单独有界后台线程；准备与投递分开，重启不自动重放在途模型或渠道请求。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifeReviewRuntime {
    private final LifeReviewRepository repository;
    private final LifeReviewService reviews;
    private final LifeOutreachService outreach;
    private final ResetBarrier barrier;
    private final Clock automationClock;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "life-review-worker"); t.setDaemon(true); return t;
    });
    @EventListener(ApplicationReadyEvent.class)
    /** 迁移就绪后恢复未知状态，再启动有界后台循环。 */
    public void start() {
        if (!barrier.blocked()) repository.recover();
        worker.scheduleWithFixedDelay(this::tick, 10, 30, TimeUnit.SECONDS);
    }
    /** 按授权准备每日内容和到期候选，重置保护期间跳过。 */
    public void tick() {
        if (barrier.blocked()) return;
        try (var lease = barrier.enter()) {
            for (var s : repository.enabled()) {
                try {
                    var local = automationClock.instant().atZone(ZoneId.of(s.timezone()));
                    if (local.getHour() >= 21 && repository.latest(s.owner(), local.toLocalDate()).isEmpty())
                        reviews.prepare(s.owner(), new LifeReviewDtos.Prepare(local.toLocalDate(), 0, false));
                    outreach.tick(s.owner());
                } catch (RuntimeException e) { log.warn("生活复盘调度跳过：{}", e.getClass().getSimpleName()); }
            }
            reviews.tick();
        } catch (RuntimeException e) { log.warn("生活复盘后台暂停：{}", e.getClass().getSimpleName()); }
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
