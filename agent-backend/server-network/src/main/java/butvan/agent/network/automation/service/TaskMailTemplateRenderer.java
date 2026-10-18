package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.MailTemplate;
import butvan.agent.network.automation.model.TaskSpec;
import org.springframework.web.util.HtmlUtils;
import java.util.Locale;

/** 使用邮件兼容的内联样式和表格布局渲染唯一的蓝调摘要模板。 */
public final class TaskMailTemplateRenderer {
    private static final String ACCENT = "#2458a6";

    private TaskMailTemplateRenderer() {}

    /** 报告栏目逐项转义后渲染；运行快照保存完整 HTML，重试不会重新生成。 */
    public static String render(TaskSpec spec, TaskReportBuilder.Report report) {
        boolean isReport = spec.isReport();
        MailTemplate template = isReport ? spec.mailTemplate() : MailTemplate.defaults();
        template.validate();

        String heading = isReport ? spec.reportLabel() + " · 生活摘要" : spec.title();
        String intro = isReport ? "让每一段进展都有迹可循。" : "";
        String period = isReport ? report.date() : "任务提醒";
        StringBuilder body = new StringBuilder();
        if (report.sections().isEmpty()) {
            body.append(paragraph(report.text()));
        } else {
            int number = 1;
            for (var section : report.sections()) body.append(section(section, number++));
        }

        String header = "<p style='margin:0 0 22px;font-size:11px;font-weight:700;letter-spacing:2px;color:" + ACCENT + "'>"
                + escape(spec.reportLabel().toUpperCase(Locale.ROOT)) + "</p>"
                + "<h1 style='margin:0 0 10px;font-size:30px;line-height:1.35;color:#1e293b'>" + escape(heading) + "</h1>"
                + "<p style='margin:0 0 8px;font-size:14px;color:#69758b;line-height:1.7'>" + escape(intro) + "</p>"
                + "<p style='margin:0 0 28px;font-size:12px;color:" + ACCENT + ";line-height:1.7'>" + escape(period) + "</p>";
        String closing = "<p style='margin:28px 0 0;border-top:1px solid #e5eaf1;padding-top:20px;font-size:12px;color:#8390a2;line-height:1.8'>"
                + (report.generatedAt().isBlank() ? "" : "生成于 " + escape(report.generatedAt()) + "<br>")
                + "ButvanAgent · 为你的每段日常留下一份记录</p>";

        return "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:#edf3fa;color:#253149;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Arial,sans-serif'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td align='center' style='padding:32px 12px'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='max-width:640px;background:#ffffff;border-radius:18px'><tr><td style='padding:36px 32px'>"
                + header + body + (report.partial() ? "<p style='padding:12px;background:#fff4e5;color:#8c5b18;font-size:13px'>部分来源暂不可用，已标注在相应栏目。</p>" : "")
                + closing + "</td></tr></table></td></tr></table></body></html>";
    }

    private static String section(TaskReportBuilder.Section section, int number) {
        StringBuilder html = new StringBuilder("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin:0 0 16px'><tr><td style='background:#f3f7fd;border-radius:12px;padding:22px'>")
                .append("<p style='margin:0 0 12px;font-size:11px;letter-spacing:1px;color:").append(ACCENT).append("'>")
                .append(String.format(Locale.ROOT, "%02d", number)).append(" / ").append(escape(section.title())).append("</p>");
        if (!section.metrics().isEmpty()) {
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='5' style='border-collapse:collapse;font-size:13px'>")
                    .append("<tr style='color:#7d899c'><th align='left'>指标</th><th align='right'>本期</th><th align='right'>上期</th><th align='right'>变化</th></tr>");
            for (var metric : section.metrics()) html.append("<tr style='border-top:1px solid #dde4ed'><td>")
                    .append(escape(metric.label())).append("</td><td align='right' style='font-weight:700'>")
                    .append(escape(metric.current())).append("</td><td align='right'>")
                    .append(escape(metric.previous())).append("</td><td align='right' style='color:").append(ACCENT).append("'>")
                    .append(escape(metric.change())).append("</td></tr>");
            html.append("</table>");
        }
        if (!section.detail().isBlank()) html.append("<div style='margin:12px 0 0;font-size:12px;line-height:1.7;color:#758196'>")
                .append(escape(section.detail()).replace("\n", "<br>")).append("</div>");
        return html.append("</td></tr></table>").toString();
    }

    private static String paragraph(String text) {
        return "<p style='margin:0;font-size:14px;line-height:1.85;white-space:normal;overflow-wrap:anywhere;word-break:break-word'>"
                + escape(text).replace("\n", "<br>") + "</p>";
    }

    private static String escape(String text) { return HtmlUtils.htmlEscape(text == null ? "" : text); }
}
