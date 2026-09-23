package butvan.agent.network.automation.model;

import java.time.*;
import java.util.Set;

/** 自动任务的受控规则；固定提醒不调用模型，使用时长由原生端采集。 */
public record TaskSpec(String title, String content, String kind, String trigger,
        String timezone, String atTime, String onceAt, int weekdays, int minutes,
        int breakMinutes, String windowStart, String windowEnd,
        boolean desktop, boolean email, boolean confirm, boolean sound,
        boolean expense, boolean todo, boolean study, MailTemplate mailTemplate) {
    public TaskSpec { mailTemplate = mailTemplate == null ? MailTemplate.defaults() : mailTemplate; }
    /** 兼容未配置邮件模板的已有调用。 */
    public TaskSpec(String title, String content, String kind, String trigger, String timezone,
            String atTime, String onceAt, int weekdays, int minutes, int breakMinutes,
            String windowStart, String windowEnd, boolean desktop, boolean email, boolean confirm,
            boolean sound, boolean expense, boolean todo, boolean study) {
        this(title, content, kind, trigger, timezone, atTime, onceAt, weekdays, minutes, breakMinutes,
                windowStart, windowEnd, desktop, email, confirm, sound, expense, todo, study, MailTemplate.defaults());
    }

    /** 在领域入口校验配置，避免非法规则写入后无法恢复。 */
    public void validate() {
        mailTemplate.validate();
        if (title == null || title.isBlank() || title.length() > 100) invalid("标题须为 1 至 100 字");
        if (content == null || content.length() > 4000) invalid("提醒内容不能超过 4000 字");
        if (!Set.of("REMINDER", "DAILY_REPORT", "SEDENTARY").contains(kind == null ? "" : kind)) invalid("任务类型不支持");
        if (!Set.of("ONCE", "DAILY", "WEEKLY", "INTERVAL", "ACTIVITY").contains(trigger == null ? "" : trigger)) invalid("触发方式不支持");
        try {
            ZoneId.of(timezone);
            LocalTime.parse(atTime);
            LocalTime.parse(windowStart);
            LocalTime.parse(windowEnd);
            if ("ONCE".equals(trigger)) Instant.parse(onceAt);
        } catch (DateTimeException | NullPointerException e) { invalid("任务日期、时间或时区不合法"); }
        if (weekdays < 1 || weekdays > 127) invalid("至少选择一个生效星期");
        if (minutes < 1 || minutes > 1440 || breakMinutes < 1 || breakMinutes > 120) invalid("计时范围不合法");
        if (!desktop && !email) invalid("至少选择一种通知方式");
        if (confirm && !desktop) invalid("手动确认需要开启桌面提醒");
        if ("SEDENTARY".equals(kind) != "ACTIVITY".equals(trigger)) invalid("久坐任务必须使用电脑使用时长触发");
        if ("SEDENTARY".equals(kind) && !confirm) invalid("久坐提醒需要手动确认");
        if ("DAILY_REPORT".equals(kind) && (!"DAILY".equals(trigger) || !(expense || todo || study))) invalid("日报需每日触发并选择至少一个栏目");
    }
    /** 判断本地时刻是否处于用户指定的生效范围；相同时刻表示全天。 */
    public boolean activeAt(Instant instant) {
        var local = instant.atZone(ZoneId.of(timezone));
        var start = LocalTime.parse(windowStart);
        var end = LocalTime.parse(windowEnd);
        var time = local.toLocalTime();
        boolean crossesMidnight = start.isAfter(end);
        int weekday = local.getDayOfWeek().getValue();
        // 跨午夜的凌晨时段属于前一天的生效星期。
        if (crossesMidnight && time.isBefore(end)) weekday = weekday == 1 ? 7 : weekday - 1;
        if ((weekdays & (1 << (weekday - 1))) == 0) return false;
        return start.equals(end) || (start.isBefore(end)
                ? !time.isBefore(start) && time.isBefore(end)
                : !time.isBefore(start) || time.isBefore(end));
    }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
}
