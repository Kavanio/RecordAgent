package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.*;
import butvan.agent.network.automation.repository.AutomationRepository;
import butvan.agent.network.automation.dto.AutomationDtos.*;
import butvan.agent.network.automation.event.TasksChanged;
import butvan.agent.network.review.service.ReviewCoachService;
import butvan.agent.network.review.service.ReviewDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** 自动任务唯一写入入口；本机串行操作与 SQLite 事务共同保护领取、暂停和确认。 */
@Service
@RequiredArgsConstructor
public class AutomationService {
    private final AutomationRepository repository;
    private final TaskReportBuilder reports;
    private final TaskMailService mail;
    private final ApplicationEventPublisher events;
    private final PlatformTransactionManager transactions;
    private final Clock automationClock;
    private final ReviewCoachService reviews;
    private final ReviewDeliveryService reviewDeliveries;
    /** 仅保存最后一次原生采样时间，无法观察的间隙绝不推算为使用时长。 */
    private final Map<String, Instant> samples = new HashMap<>();
    /** 同步读取时分配序号，跨 REST/SSE 传输可比较；重启更换来源，不持久化运行态序号。 */
    private final String snapshotSource = UUID.randomUUID().toString();
    private long snapshotRevision;

    private <T> T transaction(String owner, Supplier<T> action) { return transaction(owner, false, action); }
    private <T> T transaction(String owner, boolean activityOnly, Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> {
            T result = action.get(); events.publishEvent(new TasksChanged(owner, activityOnly)); return result;
        });
    }
    private AutomationTask require(String owner, String id) {
        return repository.find(owner, id).filter(t -> !"DELETED".equals(t.status()))
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }
    /** 有界任务列表和待处理桌面记录供 SSE 首次及重连快照使用。 */
    public synchronized Snapshot snapshot(String owner) {
        int expired = new TransactionTemplate(transactions).execute(status ->
                repository.expireDesktopNotices(owner, automationClock.millis() - Duration.ofMinutes(15).toMillis()));
        if (expired > 0) events.publishEvent(new TasksChanged(owner));
        return new Snapshot(repository.list(owner).stream().map(TaskView::from).toList(),
                repository.pending(owner).stream().map(RunView::from).toList(), false, snapshotSource, ++snapshotRevision);
    }
    /** 创建或更新任务，版本冲突拒绝覆盖。 */
    public synchronized TaskView save(String owner, String id, SaveRequest input) {
        if (input == null || input.spec() == null) throw new IllegalArgumentException("任务配置不能为空");
        TaskSpec s = input.spec().domain(); s.validate();
        if (input.enabled() && s.email() && !mail.settings().ready()) throw new IllegalArgumentException("请先配置邮件服务并绑定收件邮箱");
        Instant now = automationClock.instant();
        Long next = input.enabled() ? TaskSchedule.next(s, now) : null;
        if (input.enabled() && "ONCE".equals(s.trigger()) && next == null) throw new IllegalArgumentException("一次性任务时间必须在未来且位于生效时段内");
        return transaction(owner, () -> {
            AutomationTask old = id == null ? null : require(owner, id);
            if (old == null && repository.list(owner).stream().filter(t -> !"DELETED".equals(t.status())).count() >= 200) throw new IllegalArgumentException("最多创建 200 个任务");
            if (old != null && repository.waiting(id)) throw new IllegalArgumentException("请先确认或暂停当前提醒再编辑任务");
            AutomationTask saved = new AutomationTask(id == null ? UUID.randomUUID().toString() : id, owner, s,
                    input.enabled() ? "ENABLED" : "PAUSED", old == null ? 1 : old.version() + 1, next, 0, now.toEpochMilli());
            if (old == null) repository.insert(saved); else { repository.update(saved, input.version()); repository.withdraw(id); }
            return TaskView.from(saved);
        });
    }
    /** 暂停/恢复重新计算下一次，不补发暂停期间历史。 */
    public synchronized TaskView state(String owner, String id, StateRequest input) {
        var old = require(owner, id);
        if (input.enabled() && old.spec().email() && !mail.settings().ready()) throw new IllegalArgumentException("请先完成邮件配置");
        return transaction(owner, () -> {
            Long next = input.enabled() ? TaskSchedule.next(old.spec(), automationClock.instant()) : null;
            if (input.enabled() && "ONCE".equals(old.spec().trigger()) && next == null) throw new IllegalArgumentException("一次性任务已过期，请修改时间");
            var value = new AutomationTask(id, owner, old.spec(), input.enabled() ? "ENABLED" : "PAUSED",
                    old.version() + 1, next, 0, automationClock.millis());
            repository.update(value, input.version());
            if (!input.enabled()) repository.withdraw(id);
            return TaskView.from(value);
        });
    }
    /** 软删除保留历史，撤回未投递通知。 */
    public synchronized void delete(String owner, String id, int version) {
        var old = require(owner, id);
        transaction(owner, () -> {
            repository.update(new AutomationTask(id, owner, old.spec(), "DELETED", old.version()+1, null, 0, automationClock.millis()), version);
            repository.withdraw(id); return null;
        });
    }
    /** 从回收站恢复为暂停状态，避免立即触发已过期的计划。 */
    public synchronized TaskView restore(String owner, String id, int version) {
        return transaction(owner, () -> {
            var old = repository.find(owner, id).filter(task -> "DELETED".equals(task.status()))
                    .orElseThrow(() -> new IllegalArgumentException("已删除任务不存在"));
            if (repository.list(owner).stream().filter(task -> !"DELETED".equals(task.status())).count() >= 200)
                throw new IllegalArgumentException("最多保留 200 个任务，请先删除其他任务");
            var restored = new AutomationTask(id, owner, old.spec(), "PAUSED", old.version() + 1, null, 0, automationClock.millis());
            repository.update(restored, version);
            return TaskView.from(restored);
        });
    }
    /** 用户确认后永久删除当前用户全部已删除任务及其执行历史。 */
    public synchronized int purgeDeleted(String owner) {
        return transaction(owner, () -> repository.purgeDeleted(owner));
    }
    /** 预览只生成内容与下次时间，不写执行记录也不发送。 */
    public synchronized Preview preview(String owner, Spec input) {
        if (input == null) throw new IllegalArgumentException("任务配置不能为空");
        var s = input.domain(); s.validate(); var now = automationClock.instant();
        var report = reports.build(owner, s, now, now);
        return new Preview(s.title(), report.text(), TaskSchedule.next(s, now), TaskMailTemplateRenderer.render(s, report));
    }
    /** 手动执行仅允许启用任务，不改变下一次计划时间。 */
    public synchronized RunView runNow(String owner, String id) {
        var task = require(owner, id);
        if (!"ENABLED".equals(task.status())) throw new IllegalArgumentException("请先启用任务");
        if (repository.waiting(id)) throw new IllegalArgumentException("已有待确认提醒，请先处理");
        return transaction(owner, () -> RunView.from(execute(task, automationClock.millis(), "MANUAL")));
    }
    /** 返回任务最近 50 次历史，已删除任务也可读取。 */
    public synchronized List<RunView> history(String owner, String id) {
        return repository.history(owner, id).stream().map(RunView::from).toList();
    }
    /** 到期调度，离线只补当天最近的报告，普通提醒不连续补发。 */
    public synchronized void tick() {
        var now = automationClock.instant();
        for (var task : repository.enabled()) {
            if (task.nextAt() == null || task.nextAt() > now.toEpochMilli()) continue;
            transaction(task.ownerId(), () -> {
                long planned = task.nextAt();
                boolean due = now.toEpochMilli() - planned <= 60_000;
                if (task.spec().isReport()) {
                    Long latest = TaskSchedule.next(task.spec(), now.atZone(ZoneId.of(task.spec().timezone())).toLocalDate().atStartOfDay(ZoneId.of(task.spec().timezone())).toInstant().minusNanos(1));
                    if (latest != null && latest <= now.toEpochMilli()) planned = latest;
                    due = Instant.ofEpochMilli(planned).atZone(ZoneId.of(task.spec().timezone())).toLocalDate()
                            .equals(now.atZone(ZoneId.of(task.spec().timezone())).toLocalDate());
                }
                if (due && task.spec().activeAt(now) && !repository.waiting(task.id())) execute(task, planned, "SCHEDULED");
                else skipped(task, planned, "已错过生效时间或存在待确认提醒");
                Long next = TaskSchedule.next(task.spec(), now);
                repository.progress(task.id(), next, task.activeSeconds(), "ONCE".equals(task.spec().trigger()) ? "FINISHED" : "ENABLED");
                return null;
            });
        }
    }
    private TaskRun execute(AutomationTask task, long planned, String source) {
        var s = task.spec(); var now = automationClock.instant();
        String text, status, error = "", html = "";
        try {
            var report = reports.build(task.ownerId(), s, Instant.ofEpochMilli(planned), now);
            text = report.text(); html = TaskMailTemplateRenderer.render(s, report); status = report.partial() ? "PARTIAL" : "SUCCESS";
        } catch (RuntimeException e) {
            text = ""; status = "FAILED"; error = "任务内容生成失败，请检查数据来源后重试";
        }
        String recipient = s.email() ? mail.recipient() : null;
        boolean failed = "FAILED".equals(status);
        // 只有成功生成且启用邮件的报告才进入 AI 等待；失败、非报告或未授权时保持原流程。
        String reviewId = null;
        String reviewError = "";
        if (!failed && s.isReport() && s.email()) {
            try {
                var period = ReportPeriod.completed(s.kind(), Instant.ofEpochMilli(planned), ZoneId.of(s.timezone()));
                // 日报的事实必须是已结束自然日，因此复盘昨天；周报／月报使用已结束周期。
                var reviewFrom = "DAILY_REPORT".equals(s.kind()) ? period.from().minusDays(1) : period.from();
                reviewId = reviews.schedule(task.ownerId(), s.kind(), reviewFrom, period.to(), s.timezone()).orElse(null);
            } catch (RuntimeException exception) {
                reviewError = "AI 复盘未启用，已发送基础报告";
            }
        }
        boolean waiting = reviewId != null;
        var run = new TaskRun(UUID.randomUUID().toString(), task.id(), task.ownerId(), s.title(), text,
                planned, now.toEpochMilli(), source, status, s.desktop() && !failed ? "PENDING" : "SKIPPED",
                s.email() && !failed && recipient != null ? (waiting ? "WAITING" : "PENDING") : "SKIPPED", recipient,
                s.confirm() && !failed ? "WAITING" : "NONE", s.sound(), reviewError, html, now.toEpochMilli());
        repository.insertRun(run);
        if (waiting) reviewDeliveries.register(task.ownerId(), run.id(), reviewId, text, html,
                "DAILY_REPORT".equals(s.kind()) ? 90 : "WEEKLY_REPORT".equals(s.kind()) ? 120 : 150);
        if ("ACTIVITY".equals(s.trigger())) repository.progress(task.id(), null, 0, task.status());
        return run;
    }
    private void skipped(AutomationTask t, long planned, String reason) {
        repository.insertRun(new TaskRun(UUID.randomUUID().toString(), t.id(), t.ownerId(), t.spec().title(), "",
                planned, automationClock.millis(), "SCHEDULED", "SKIPPED", "SKIPPED", "SKIPPED", null, "NONE", false, reason));
    }
    /** 原生采集状态输入；空闲、锁屏或采样中断时暂停计时并保留已累计时长。 */
    public synchronized void activity(String owner, ActivitySample sample) {
        if (sample.idleSeconds() < 0) throw new IllegalArgumentException("空闲时长不合法");
        Instant now = automationClock.instant(); Instant previous = samples.put(owner, now);
        long delta = previous == null ? 0 : Duration.between(previous, now).getSeconds();
        for (var task : repository.list(owner)) {
            if (!"ENABLED".equals(task.status()) || !"ACTIVITY".equals(task.spec().trigger()) || repository.waiting(task.id())) continue;
            var spec = task.spec();
            boolean activelyUsed = sample.supported() && !sample.locked() && spec.activeAt(now)
                    && sample.idleSeconds() < spec.breakMinutes()*60L;
            // 首次采样、长间隔及非活动状态只暂停；不能据此推算遗漏时间或清除已保存进度。
            long seconds = task.activeSeconds() + (activelyUsed && delta > 0 && delta <= 15 ? delta : 0);
            boolean executeNow = seconds >= spec.minutes()*60L;
            transaction(owner, !executeNow, () -> {
                repository.progress(task.id(), null, seconds, task.status());
                if (executeNow) execute(task, now.toEpochMilli(), "ACTIVITY");
                return null;
            });
        }
    }
    /** 只复用已保存内容；未知状态需用户明确承认可能重复。 */
    public synchronized void retryMail(String owner, String runId, boolean allowUnknown) {
        var run = repository.run(owner, runId);
        if (!"FAILED".equals(run.emailStatus()) && !(allowUnknown && "UNKNOWN".equals(run.emailStatus())))
            throw new IllegalArgumentException("这封邮件当前不可重发");
        if (run.recipient() == null || !run.recipient().equals(mail.recipient()) || !mail.settings().ready())
            throw new IllegalArgumentException("收件邮箱已变更或邮件配置不可用");
        if (repository.find(owner, run.taskId()).map(t -> !"DELETED".equals(t.status())).orElse(false) == false)
            throw new IllegalArgumentException("任务已删除，不能重发邮件");
        transaction(owner, () -> {
            if (!repository.retryMail(owner, runId, run.emailStatus(), automationClock.millis()))
                throw new IllegalArgumentException("邮件状态已变化，请刷新后重试");
            return null;
        });
    }
    /** 原子领取桌面通知，避免多个页面重复投递。 */
    public synchronized boolean claimDesktop(String owner, String id) {
        return transaction(owner, () -> repository.claimDesktop(owner, id));
    }
    /** 系统已提交不等于已阅读；失败回执也保留待确认窗口。 */
    public synchronized void desktopReceipt(String owner, String id, String status) {
        if (!Set.of("SUBMITTED", "FAILED", "SKIPPED").contains(status)) throw new IllegalArgumentException("通知状态不合法");
        repository.run(owner, id);
        transaction(owner, () -> { repository.desktopResult(id, status); return null; });
    }
    /** 重复确认不会再次重置已开始的下一轮计时。 */
    public synchronized void confirm(String owner, String id) {
        var run = repository.run(owner, id);
        if (!"WAITING".equals(run.confirmation())) return;
        transaction(owner, () -> {
            repository.confirm(id);
            var task = require(owner, run.taskId());
            if ("ACTIVITY".equals(task.spec().trigger())) repository.progress(task.id(), null, 0, task.status());
            samples.remove(owner); return null;
        });
    }
    /** 重启后标记不确定投递；无法观察的离线时段暂停计时，保留已累计的使用时长。 */
    public synchronized void recover() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            repository.recover();
            for (var task : repository.enabled()) if ("INTERVAL".equals(task.spec().trigger()))
                repository.progress(task.id(), TaskSchedule.next(task.spec(), automationClock.instant()), 0, task.status());
        });
        samples.clear();
    }
    /** 邮件线程领取单次投递；网络操作在事务和领域锁外执行。 */
    public synchronized TaskRun claimMail() {
        for (var run : repository.pendingMail()) {
            if (automationClock.millis()-run.emailQueuedAt() > 86_400_000) {
                transaction(run.ownerId(), () -> { repository.mailResult(run.id(), "SKIPPED", "邮件排队已超过 24 小时"); return null; });
                continue;
            }
            if (transaction(run.ownerId(), () -> repository.claimMail(run.id()))) return run;
        }
        return null;
    }
    /** 保存一次邮件投递结果并通知界面。 */
    public synchronized void mailResult(TaskRun run, TaskMailService.Outcome outcome) {
        transaction(run.ownerId(), () -> { repository.mailResult(run.id(), outcome.status(), outcome.message()); return null; });
    }
}
