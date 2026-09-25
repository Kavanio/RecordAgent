package butvan.agent.network.automation.repository;

import butvan.agent.network.automation.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/** 仅访问自动任务领域表，调用方负责事务与当前用户边界。 */
@Repository
@RequiredArgsConstructor
public class AutomationRepository {
    private final JdbcTemplate jdbc;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static String encodeTemplate(MailTemplate template) {
        try { return JSON.writeValueAsString(template); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("邮件模板无法保存", e); }
    }
    private static MailTemplate decodeTemplate(String json) {
        try { return JSON.readValue(json, MailTemplate.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("邮件模板无法读取", e); }
    }
    private static final RowMapper<AutomationTask> TASK = (rs, row) -> new AutomationTask(
            rs.getString("id"), rs.getString("owner_id"), new TaskSpec(
                rs.getString("rule_title"),
                rs.getString("rule_content"),
                rs.getString("rule_kind"),
                rs.getString("rule_trigger"),
                rs.getString("rule_timezone"),
                rs.getString("rule_at_time"),
                rs.getString("rule_once_at"),
                rs.getInt("rule_weekdays"),
                rs.getInt("rule_minutes"),
                rs.getInt("rule_break_minutes"),
                rs.getString("rule_window_start"),
                rs.getString("rule_window_end"),
                rs.getBoolean("rule_desktop"),
                rs.getBoolean("rule_email"),
                rs.getBoolean("rule_confirm"),
                rs.getBoolean("rule_sound"),
                rs.getBoolean("rule_expense"),
                rs.getBoolean("rule_todo"),
                rs.getBoolean("rule_study"), decodeTemplate(rs.getString("mail_template"))), rs.getString("status"), rs.getInt("version"),
            rs.getObject("next_at") == null ? null : rs.getLong("next_at"), rs.getLong("active_seconds"), rs.getLong("updated_at"));
    private static final RowMapper<TaskRun> RUN = (rs, row) -> new TaskRun(rs.getString("id"), rs.getString("task_id"),
            rs.getString("owner_id"), rs.getString("title"), rs.getString("content"), rs.getLong("planned_at"),
            rs.getLong("created_at"), rs.getString("source"), rs.getString("status"), rs.getString("desktop_status"),
            rs.getString("email_status"), rs.getString("recipient"), rs.getString("confirmation"), rs.getBoolean("sound"), rs.getString("error"), rs.getString("email_html"), rs.getLong("email_queued_at"));

    /** 查询当前用户可见配置。 */
    public List<AutomationTask> list(String owner) {
        return jdbc.query("SELECT * FROM automation_task WHERE owner_id=? ORDER BY updated_at DESC", TASK, owner);
    }
    /** 调度只扫描启用任务。 */
    public List<AutomationTask> enabled() { return jdbc.query("SELECT * FROM automation_task WHERE status='ENABLED'", TASK); }
    /** 查询带所有权约束的任务。 */
    public Optional<AutomationTask> find(String owner, String id) {
        return jdbc.query("SELECT * FROM automation_task WHERE owner_id=? AND id=?", TASK, owner, id).stream().findFirst();
    }
    /** 永久清空当前用户回收站；先删除历史以满足任务外键约束。 */
    public int purgeDeleted(String owner) {
        jdbc.update("DELETE FROM automation_run WHERE owner_id=? AND task_id IN (SELECT id FROM automation_task WHERE owner_id=? AND status='DELETED')", owner, owner);
        return jdbc.update("DELETE FROM automation_task WHERE owner_id=? AND status='DELETED'", owner);
    }
    /** 保存完整配置，正式规则使用独立列而非自由 JSON。 */
    public void insert(AutomationTask task) {
        TaskSpec s = task.spec();
        jdbc.update("INSERT INTO automation_task(id,owner_id,rule_title,rule_content,rule_kind,rule_trigger,rule_timezone,rule_at_time,rule_once_at,rule_weekdays,rule_minutes,rule_break_minutes,rule_window_start,rule_window_end,rule_desktop,rule_email,rule_confirm,rule_sound,rule_expense,rule_todo,rule_study,status,version,next_at,active_seconds,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                task.id(), task.ownerId(), s.title(), s.content(), s.kind(), s.trigger(), s.timezone(), s.atTime(), s.onceAt(), s.weekdays(), s.minutes(), s.breakMinutes(), s.windowStart(), s.windowEnd(), s.desktop(), s.email(), s.confirm(), s.sound(), s.expense(), s.todo(), s.study(), task.status(), task.version(), task.nextAt(), task.activeSeconds(), task.updatedAt());
        saveTemplate(task);
    }
    /** 乐观锁修改，避免多窗口覆盖。 */
    public void update(AutomationTask task, int expectedVersion) {
        TaskSpec s = task.spec();
        int count = jdbc.update("UPDATE automation_task SET rule_title=?,rule_content=?,rule_kind=?,rule_trigger=?,rule_timezone=?,rule_at_time=?,rule_once_at=?,rule_weekdays=?,rule_minutes=?,rule_break_minutes=?,rule_window_start=?,rule_window_end=?,rule_desktop=?,rule_email=?,rule_confirm=?,rule_sound=?,rule_expense=?,rule_todo=?,rule_study=?,status=?,version=?,next_at=?,active_seconds=?,updated_at=? WHERE id=? AND owner_id=? AND version=?",
                s.title(), s.content(), s.kind(), s.trigger(), s.timezone(), s.atTime(), s.onceAt(), s.weekdays(), s.minutes(), s.breakMinutes(), s.windowStart(), s.windowEnd(), s.desktop(), s.email(), s.confirm(), s.sound(), s.expense(), s.todo(), s.study(), task.status(), task.version(), task.nextAt(), task.activeSeconds(), task.updatedAt(), task.id(), task.ownerId(), expectedVersion);
        if (count != 1) throw new IllegalArgumentException("任务已被修改，请刷新后重试");
        saveTemplate(task);
    }
    private void saveTemplate(AutomationTask task) {
        jdbc.update("UPDATE automation_task SET mail_template=? WHERE id=?",
                encodeTemplate(task.spec().mailTemplate()), task.id());
    }
    /** 调度推进不修改用户配置版本。 */
    public void progress(String id, Long nextAt, long seconds, String status) {
        jdbc.update("UPDATE automation_task SET next_at=?,active_seconds=?,status=? WHERE id=?", nextAt, seconds, status, id);
    }
    /** 保存一次执行；数据库唯一约束抵御重复计划实例。 */
    public void insertRun(TaskRun r) {
        jdbc.update("INSERT INTO automation_run(id,task_id,owner_id,title,content,planned_at,created_at,source,status,desktop_status,email_status,recipient,confirmation,sound,error,email_html,email_queued_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", r.id(), r.taskId(), r.ownerId(),
                r.title(), r.content(), r.plannedAt(), r.createdAt(), r.source(), r.status(), r.desktopStatus(),
                r.emailStatus(), r.recipient(), r.confirmation(), r.sound(), r.error(), r.emailHtml(), r.emailQueuedAt());
    }
    /** 有界读取历史，包含软删除任务的结果。 */
    public List<TaskRun> history(String owner, String taskId) {
        return jdbc.query("SELECT * FROM automation_run WHERE owner_id=? AND task_id=? ORDER BY created_at DESC LIMIT 50", RUN, owner, taskId);
    }
    /** 桌面重连恢复所有未处理通知及待确认提醒。 */
    public List<TaskRun> pending(String owner) {
        return jdbc.query("SELECT * FROM automation_run WHERE owner_id=? AND (desktop_status='PENDING' OR confirmation='WAITING') ORDER BY created_at", RUN, owner);
    }
    /** 按所有者查找执行。 */
    public TaskRun run(String owner, String id) {
        return jdbc.query("SELECT * FROM automation_run WHERE owner_id=? AND id=?", RUN, owner, id).stream()
                .findFirst().orElseThrow(() -> new IllegalArgumentException("执行记录不存在"));
    }
    /** 检查同任务未确认状态，避免重复提醒。 */
    public boolean waiting(String taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM automation_run WHERE task_id=? AND confirmation='WAITING'", Integer.class, taskId) > 0;
    }
    /** 暂停或删除时撤回未开始的通知。 */
    public void withdraw(String taskId) {
        jdbc.update("UPDATE automation_run SET confirmation=CASE WHEN confirmation='WAITING' THEN 'WITHDRAWN' ELSE confirmation END, "
                + "desktop_status=CASE WHEN desktop_status='PENDING' THEN 'SKIPPED' ELSE desktop_status END, "
                + "email_status=CASE WHEN email_status='PENDING' THEN 'SKIPPED' ELSE email_status END WHERE task_id=?", taskId);
    }
    /** 原子领取桌面投递，只有一窗口可获得执行权。 */
    public boolean claimDesktop(String owner, String id) {
        return jdbc.update("UPDATE automation_run SET desktop_status='CLAIMED' WHERE owner_id=? AND id=? AND desktop_status='PENDING'", owner, id) == 1;
    }
    /** 写入投递回执，渠道名由内部固定入口决定。 */
    public void desktopResult(String id, String status) {
        jdbc.update("UPDATE automation_run SET desktop_status=? WHERE id=? AND desktop_status='CLAIMED'", status, id);
    }
    /** 幂等确认，不改变发送状态。 */
    public void confirm(String id) { jdbc.update("UPDATE automation_run SET confirmation='CONFIRMED' WHERE id=? AND confirmation='WAITING'", id); }
    /** 返回有界待发队列。 */
    public List<TaskRun> pendingMail() { return jdbc.query("SELECT * FROM automation_run WHERE email_status='PENDING' ORDER BY created_at LIMIT 20", RUN); }
    /** 原子领取邮件；进程中断后不可盲目重复发送。 */
    public boolean claimMail(String id) { return jdbc.update("UPDATE automation_run SET email_status='SENDING' WHERE id=? AND email_status='PENDING'", id) == 1; }
    /** 邮件结果不覆盖报告正文和桌面渠道状态。 */
    public void mailResult(String id, String status, String error) { jdbc.update("UPDATE automation_run SET email_status=?,error=? WHERE id=?", status, error, id); }
    /** 用户显式重发失败或不确定投递，重新进入单次投递队列。 */
    public boolean retryMail(String owner, String id, String currentStatus, long queuedAt) {
        return jdbc.update("UPDATE automation_run SET email_status='PENDING',error='',email_queued_at=? WHERE owner_id=? AND id=? AND email_status=?",
                queuedAt, owner, id, currentStatus) == 1;
    }
    /** 恢复不确定投递，并重置无法观察的离线使用时长。 */
    public void recover() {
        jdbc.update("UPDATE automation_run SET email_status='UNKNOWN',error='上次发送中断，结果未知，请检查收件箱' WHERE email_status='SENDING'");
        jdbc.update("UPDATE automation_run SET desktop_status='UNKNOWN' WHERE desktop_status='CLAIMED'");
        jdbc.update("UPDATE automation_task SET active_seconds=0 WHERE rule_trigger='ACTIVITY'");
    }
}
