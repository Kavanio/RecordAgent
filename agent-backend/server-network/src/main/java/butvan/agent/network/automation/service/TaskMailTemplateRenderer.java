package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.MailTemplate;
import butvan.agent.network.automation.model.TaskSpec;
import org.springframework.web.util.HtmlUtils;
import java.util.Locale;

/** 使用留白、轻量色彩和邮件兼容表格渲染唯一的蓝调摘要模板。 */
public final class TaskMailTemplateRenderer {
    private static final String INK = "#20211f";
    private static final String MUTED = "#92928e";
    private static final String GREEN = "#467460";
    private static final String RED = "#ac685c";

    private TaskMailTemplateRenderer() {}

    /** 报告栏目逐项转义后渲染；运行快照保存完整 HTML，重试不会重新生成。 */
    public static String render(TaskSpec spec, TaskReportBuilder.Report report) {
        boolean isReport = spec.isReport();
        MailTemplate template = isReport ? spec.mailTemplate() : MailTemplate.defaults();
        template.validate();

        String title = isReport ? switch (spec.kind()) {
            case "DAILY_REPORT" -> "把今天，留在这里。";
            case "WEEKLY_REPORT" -> "把这一周，留在这里。";
            case "MONTHLY_REPORT" -> "把这个月，留在这里。";
            default -> "把这段时间，留在这里。";
        } : spec.title();
        String date = isReport ? report.date() : "任务提醒";
        String reportKind = "DAILY_REPORT".equals(spec.kind()) ? "PRIVATE DAILY REPORT" : "PRIVATE PERIOD REPORT";
        String generated = report.generatedAt().isBlank() ? "" : "生成于 " + escape(report.generatedAt());
        StringBuilder sections = new StringBuilder();
        if (report.sections().isEmpty()) {
            sections.append(paragraph(report.text()));
        } else {
            int number = 1;
            for (var section : report.sections()) sections.append(section(section, number++));
        }

        String partial = report.partial()
                ? "<p style='margin:26px 0 0;padding:12px 14px;background:#fff4e5;color:#8c5b18;font-size:12px;line-height:1.8'>部分来源暂不可用，已标注在相应栏目。</p>"
                : "";
        String html = "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:#ebebe9;color:" + INK + ";font-family:-apple-system,BlinkMacSystemFont,\"Helvetica Neue\",Arial,sans-serif'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td align='center' style='padding:22px 10px'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='max-width:680px;background:#ffffff'><tr><td style='padding:40px 44px 30px'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td style='font-family:Georgia,serif;font-size:24px;font-weight:700;letter-spacing:-1px'>butvan.</td>"
                + "<td align='right' style='font-size:9px;letter-spacing:1.3px;color:" + MUTED + "'>" + reportKind + "</td></tr></table>"
                + "<div style='margin-top:54px;font-size:10px;letter-spacing:.6px;color:" + MUTED + "'>" + escape(date) + "</div>"
                + "<h1 style='margin:14px 0 0;font-family:Georgia,\"Songti SC\",serif;font-size:44px;font-weight:400;letter-spacing:-1.8px;line-height:1.2;color:" + INK + "'>"
                + escape(title) + "，留在这里。</h1>"
                + "<p style='margin:15px 0 0;font-size:12px;color:#666;line-height:1.8'>把这段时间，整理成一份摘要。" + (generated.isBlank() ? "" : "<br>" + generated) + "</p>"
                + "<div style='margin-top:40px'>" + sections + partial + "</div>"
                + "<div style='margin-top:46px'><div style='font-family:Georgia,serif;font-size:22px;letter-spacing:-.5px'>Until tomorrow.</div>"
                + "<p style='margin:12px 0 0;font-size:10px;color:#888;line-height:1.9'>记录不只是关于数字，也关于那些认真度过的时刻。今天已经写下，明天继续。</p></div>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:26px'><tr><td style='font-size:9px;letter-spacing:.4px;color:" + MUTED + "'>butvan. / PERSONAL ARCHIVE</td>"
                + "<td align='right' style='font-size:9px;color:" + MUTED + "'>" + escape(date) + "</td></tr></table>"
                + "</td></tr></table></td></tr></table></body></html>";
        return html;
    }

    /** 栏目之间用留白区分，指标和明细不绘制重复分割线。 */
    private static String section(TaskReportBuilder.Section section, int number) {
        StringBuilder html = new StringBuilder("<div style='margin-top:38px'>")
                .append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td style='font-size:15px;font-weight:600;color:")
                .append(INK).append("'><span style='font-size:10px;color:#a6a6a6;margin-right:10px'>")
                .append(String.format(Locale.ROOT, "%02d /", number)).append("</span>")
                .append(escape(section.title())).append("</td><td align='right' style='font-size:9px;letter-spacing:.7px;color:#aaa'>")
                .append(escape(section.key().toUpperCase(Locale.ROOT))).append("</td></tr></table>");
        if (!section.metrics().isEmpty()) html.append(metrics(section.metrics()));
        if (!section.detail().isBlank()) html.append(detail(section.detail()));
        return html.append("</div>").toString();
    }

    /** 用无边框表格对齐本期、上期和变化值，适配常见邮件客户端。 */
    private static String metrics(java.util.List<TaskReportBuilder.Metric> metrics) {
        StringBuilder html = new StringBuilder("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:15px;font-size:10px;color:")
                .append(MUTED).append("'><tr><td style='padding-bottom:8px'>指标</td><td align='right' style='padding-bottom:8px'>本期</td><td align='right' style='padding-bottom:8px'>上期</td><td align='right' style='padding-bottom:8px'>变化</td></tr>");
        for (var metric : metrics) {
            String color = metric.change().startsWith("+") ? RED : metric.change().startsWith("-") ? GREEN : INK;
            html.append("<tr><td style='padding:5px 4px 5px 0;color:").append(INK).append("'>").append(escape(metric.label()))
                    .append("</td><td align='right' style='padding:5px 3px;font-variant-numeric:tabular-nums;color:").append(INK).append("'>")
                    .append(escape(metric.current())).append("</td><td align='right' style='padding:5px 3px;font-variant-numeric:tabular-nums'>")
                    .append(escape(metric.previous())).append("</td><td align='right' style='padding:5px 0 5px 3px;font-variant-numeric:tabular-nums;color:")
                    .append(color).append("'>").append(escape(metric.change())).append("</td></tr>");
        }
        return html.append("</table>").toString();
    }

    /** 按标题、账户或流水时间轻量排版明细，不为每行添加边框。 */
    private static String detail(String content) {
        StringBuilder html = new StringBuilder("<div style='margin-top:14px'>");
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.endsWith("：") || line.endsWith(":")) {
                html.append("<p style='margin:17px 0 5px;font-size:10px;letter-spacing:.2px;color:").append(MUTED).append("'>")
                        .append(escape(line)).append("</p>");
                continue;
            }
            boolean bullet = line.startsWith("• ");
            String value = bullet ? line.substring(2) : line;
            String color = value.contains("支出") ? RED : value.contains("收入") || value.contains("收益") ? GREEN : INK;
            int separator = value.indexOf(" · ");
            String first = separator >= 0 ? value.substring(0, separator) : "";
            if (bullet && separator >= 0 && first.matches("(?:\\d{4}-\\d{2}-\\d{2}\\s+)?\\d{2}:\\d{2}.*")) {
                html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin:8px 0'><tr><td width='48' valign='top' style='padding:2px 8px 0 0;font-size:9px;color:")
                        .append(MUTED).append(";font-variant-numeric:tabular-nums'>").append(escape(first.replaceFirst("^.*\\s", "")))
                        .append("</td><td valign='top' style='font-size:11px;line-height:1.7;color:").append(color).append("'>")
                        .append(escape(value.substring(separator + 3))).append("</td></tr></table>");
            } else if (bullet && value.indexOf('：') > 0) {
                int colon = value.indexOf('：');
                html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin:7px 0'><tr><td valign='top' style='font-size:11px;font-weight:600;color:")
                        .append(INK).append("'>").append(escape(value.substring(0, colon))).append("</td><td align='right' style='font-size:10px;line-height:1.7;color:")
                        .append(color).append("'>").append(escape(value.substring(colon + 1).trim())).append("</td></tr></table>");
            } else {
                html.append("<p style='margin:7px 0;font-size:11px;line-height:1.8;color:").append(color).append("'>")
                        .append(bullet ? "<span style='color:#aaa;margin-right:6px'>•</span>" : "")
                        .append(escape(value)).append("</p>");
            }
        }
        return html.append("</div>").toString();
    }

    private static String paragraph(String text) {
        return "<p style='margin:0;font-size:12px;line-height:1.9;white-space:normal;overflow-wrap:anywhere;word-break:break-word'>"
                + escape(text).replace("\n", "<br>") + "</p>";
    }

    private static String escape(String text) { return HtmlUtils.htmlEscape(text == null ? "" : text); }
}
