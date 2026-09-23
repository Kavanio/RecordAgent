package butvan.agent.network.automation.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

/** 独立的调度与邮件线程，慢 SMTP 不阻塞任务时间推进；页面关闭不停止服务。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationRuntime {
    private final AutomationService tasks;
    private final TaskMailService mail;
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(2, r -> {
        var thread = new Thread(r, "automation-worker"); thread.setDaemon(true); return thread;
    });
    /** 数据库与应用就绪后再恢复，避免尚未迁移即扫描。 */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        tasks.recover();
        workers.scheduleWithFixedDelay(() -> {
            try { tasks.tick(); } catch (RuntimeException e) { log.error("自动任务调度失败：{}", e.getClass().getSimpleName()); }
        }, 1, 5, TimeUnit.SECONDS);
        workers.scheduleWithFixedDelay(() -> {
            try {
                var run = tasks.claimMail();
                if (run != null) tasks.mailResult(run, mail.send(run.recipient(), run.id(), run.title(), run.content(), run.emailHtml()));
            } catch (RuntimeException e) { log.error("自动任务邮件处理失败：{}", e.getClass().getSimpleName()); }
        }, 2, 3, TimeUnit.SECONDS);
    }
    /** 应用退出时停止后台扫描。 */
    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
