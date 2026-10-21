package butvan.agent.network.automation.dto;

import butvan.agent.network.automation.model.*;
import java.util.List;

/** HTTP 与 SSE 的独立传输对象，不暴露数据库记录及 SMTP 目标明文。 */
public final class AutomationDtos {
    private AutomationDtos() {}
    public record Spec(String title, String content, String kind, String trigger, String timezone,
            String atTime, String onceAt, int weekdays, int minutes, int breakMinutes,
            String windowStart, String windowEnd, boolean desktop, boolean email, boolean confirm,
            boolean sound, boolean expense, boolean todo, boolean study, MailTemplateView mailTemplate) {
        public Spec { mailTemplate = mailTemplate == null ? MailTemplateView.from(MailTemplate.defaults()) : mailTemplate; }
        /** 兼容未配置邮件模板的已有调用。 */
        public Spec(String title, String content, String kind, String trigger, String timezone,
                String atTime, String onceAt, int weekdays, int minutes, int breakMinutes,
                String windowStart, String windowEnd, boolean desktop, boolean email, boolean confirm,
                boolean sound, boolean expense, boolean todo, boolean study) {
            this(title, content, kind, trigger, timezone, atTime, onceAt, weekdays, minutes, breakMinutes,
                    windowStart, windowEnd, desktop, email, confirm, sound, expense, todo, study, MailTemplateView.from(MailTemplate.defaults()));
        }

        public TaskSpec domain() { return new TaskSpec(title, content, kind, trigger, timezone, atTime,
                onceAt, weekdays, minutes, breakMinutes, windowStart, windowEnd, desktop, email,
                confirm, sound, expense, todo, study, mailTemplate.domain()); }
        public static Spec from(TaskSpec s) { return new Spec(s.title(), s.content(), s.kind(), s.trigger(),
                s.timezone(), s.atTime(), s.onceAt(), s.weekdays(), s.minutes(), s.breakMinutes(),
                s.windowStart(), s.windowEnd(), s.desktop(), s.email(), s.confirm(), s.sound(), s.expense(), s.todo(), s.study(), MailTemplateView.from(s.mailTemplate())); }
    }
    /** 模板 HTTP 字段与内部持久化模型分离，空字段由领域模型补全。 */
    public record MailTemplateView(String style, String accent, String heading, String introduction, String footer, String body) {
        public MailTemplate domain() { return new MailTemplate(style, accent, heading, introduction, footer, body); }
        public static MailTemplateView from(MailTemplate t) { return new MailTemplateView(t.style(), t.accent(), t.heading(), t.introduction(), t.footer(), t.body()); }
    }
    public record SaveRequest(Spec spec, boolean enabled, int version) {}
    public record StateRequest(boolean enabled, int version) {}
    public record TaskView(String id, Spec spec, String status, int version, Long nextAt, long activeSeconds) {
        public static TaskView from(AutomationTask t) { return new TaskView(t.id(), Spec.from(t.spec()), t.status(), t.version(), t.nextAt(), t.activeSeconds()); }
    }
    public record RunView(String id, String taskId, String title, String content, long plannedAt,
            long createdAt, String source, String status, String desktopStatus, String emailStatus,
            String confirmation, boolean sound, String error) {
        public static RunView from(TaskRun r) { return new RunView(r.id(), r.taskId(), r.title(), r.content(),
                r.plannedAt(), r.createdAt(), r.source(), r.status(), r.desktopStatus(), r.emailStatus(),
                r.confirmation(), r.sound(), r.error()); }
    }
    /** activityOnly 仅标记本次推送原因；首次、重连与 REST 读取均为完整状态刷新。 */
    public record Snapshot(List<TaskView> tasks, List<RunView> pending, boolean activityOnly, String sourceId, long revision) {
        public Snapshot(List<TaskView> tasks, List<RunView> pending) { this(tasks, pending, false, null, 0); }
        /** 保留来源和序号，仅替换本次刷新原因。 */
        public Snapshot withActivityOnly(boolean value) { return new Snapshot(tasks, pending, value, sourceId, revision); }
    }
    public record Preview(String title, String content, Long nextAt, String html) {}
    public record DesktopReceipt(String status) {}
    public record RetryEmailRequest(boolean allowUnknown) {}
    public record ActivitySample(long idleSeconds, boolean locked, boolean supported) {}
}
