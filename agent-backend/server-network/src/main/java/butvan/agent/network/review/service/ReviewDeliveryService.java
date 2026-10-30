package butvan.agent.network.review.service;

import butvan.agent.agents.review.ReviewDraft;
import butvan.agent.network.automation.event.TasksChanged;
import butvan.agent.network.review.model.ReviewModels.Delivery;
import butvan.agent.network.review.model.ReviewModels.Job;
import butvan.agent.network.review.repository.ReviewCoachRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * 邮件两段式冻结：AI 等待期间不发送，成功或到期后一次性选定副本。
 * 结果晚到不会补发第二封邮件，也不能改写已经冻结的副本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewDeliveryService {
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "DEGRADED", "SKIPPED", "UNKNOWN");
    private final ReviewCoachRepository repository;
    private final PlatformTransactionManager transactions;
    private final JdbcTemplate jdbc;
    private final Clock automationClock;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher events;

    /** 登记等待中的邮件副本；AI 未开启时调用方不登记，直接使用基础报告。 */
    public void register(String owner, String runId, String reviewId, String baseText, String baseHtml, int seconds) {
        repository.registerDelivery(new Delivery(runId, owner, reviewId, "WAITING", baseText, baseHtml,
                null, null, automationClock.instant().plusSeconds(seconds), null));
    }

    /** 由独立线程推进到期副本；单封失败不影响其他任务。 */
    public void freezeReady() {
        for (Delivery delivery : repository.waitingDeliveries()) {
            try { freeze(delivery); }
            catch (RuntimeException exception) {
                log.warn("复盘邮件冻结失败：run={} type={}", delivery.runId(), exception.getClass().getSimpleName());
            }
        }
    }

    private void freeze(Delivery delivery) {
        Job job = delivery.reviewId() == null ? null : repository.findById(delivery.reviewId()).orElse(null);
        boolean pending = job != null && !TERMINAL.contains(job.state());
        boolean expired = delivery.deadline().isBefore(automationClock.instant());
        // 仍在生成且未到期时继续等待，避免提前发送基础报告。
        if (pending && !expired) return;
        boolean succeeded = job != null && "SUCCEEDED".equals(job.state());
        String section = succeeded ? aiSection(job) : "";
        boolean ai = !section.isBlank();
        String text = ai ? delivery.baseText() + "\n\nAI 长期复盘\n" + section : delivery.baseText();
        String html = ai ? delivery.baseHtml().replace("</body>",
                "<hr><h2>AI 长期复盘</h2><pre style=\"white-space:pre-wrap;font-family:inherit\">"
                        + escape(section) + "</pre></body>") : delivery.baseHtml();
        Instant now = automationClock.instant();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (!repository.freeze(delivery.runId(), text, html, ai, now)) return;
            int updated = jdbc.update("UPDATE automation_run SET content=?,email_html=?,email_status='PENDING',email_queued_at=?,error=? "
                            + "WHERE id=? AND email_status='WAITING'",
                    text, html, now.toEpochMilli(), ai ? "" : "AI 复盘未就绪，已发送基础报告", delivery.runId());
            if (updated > 0) events.publishEvent(new TasksChanged(delivery.owner()));
        });
    }

    /** 从结构化结果渲染简短文本；正文已在生成阶段通过引用与长度校验。 */
    private String aiSection(Job job) {
        if (job.resultJson() == null || job.resultJson().isBlank()) return "";
        try {
            ReviewDraft draft = mapper.readValue(job.resultJson(), ReviewDraft.class);
            StringBuilder text = new StringBuilder(draft.coverageNote() == null ? "" : draft.coverageNote()).append('\n');
            for (var item : draft.observations()) text.append("\n观察：").append(item.text()).append('\n');
            for (var item : draft.followUps()) text.append("\n跟进：").append(item.text()).append('\n');
            for (var item : draft.actions()) text.append("\n建议：").append(item.text())
                    .append("（检查：").append(item.successCriterion()).append("）\n");
            String result = text.toString().strip();
            return result.length() > 2_000 ? result.substring(0, 2_000) : result;
        } catch (Exception exception) {
            log.warn("复盘结果渲染失败：job={}", job.id());
            return "";
        }
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
