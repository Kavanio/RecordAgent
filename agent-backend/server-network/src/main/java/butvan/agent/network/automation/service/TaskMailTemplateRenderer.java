package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.MailTemplate;
import butvan.agent.network.automation.model.TaskSpec;
import org.springframework.web.util.HtmlUtils;
import java.util.*;
import java.util.regex.*;

/** 邮件兼容的内联样式与表格布局；运行快照保存完整渲染结果。 */
public final class TaskMailTemplateRenderer {
    private TaskMailTemplateRenderer() {}
    /** 自定义模板只替换原模板中的占位符，数据内容不会再次展开。 */
    public static String render(TaskSpec spec, TaskReportBuilder.Report report) {
        boolean isReport = spec.isReport();
        MailTemplate template = isReport ? spec.mailTemplate() : MailTemplate.defaults();
        template.validate();
        String style = template.style();
        String accent = "CUSTOM".equals(style) ? template.accent() : switch (style) {
            case "EDITORIAL" -> "#242b43"; case "LEDGER" -> "#66502f"; default -> "#2458a6";
        };
        String background = switch (style) { case "EDITORIAL" -> "#ffffff"; case "LEDGER" -> "#f6f1e8"; default -> "#edf3fa"; };
        String heading = !isReport ? spec.title() : "CUSTOM".equals(style) ? template.heading() : switch (style) {
            case "EDITORIAL" -> spec.reportLabel() + " / 周期观察";
            case "LEDGER" -> spec.reportLabel() + " · 收支与进展";
            default -> spec.reportLabel() + " · 生活摘要";
        };
        String intro = !isReport ? "" : "CUSTOM".equals(style) ? template.introduction() : switch (style) {
            case "EDITORIAL" -> "回看已经结束的周期，看看变化发生在哪里。";
            case "LEDGER" -> "一页清晰账目，也是一份生活记录。";
            default -> "让每一段进展都有迹可循。";
        };
        String footer = "CUSTOM".equals(style) ? template.footer() : "ButvanAgent · 为你的每段日常留下一份记录";
        StringBuilder body = new StringBuilder();
        if ("CUSTOM".equals(style)) {
            Map<String, String> values = new HashMap<>(Map.of("title", spec.title(), "date", report.date(),
                    "generatedAt", report.generatedAt(), "content", report.text(), "expense", "", "todo", "", "study", ""));
            report.sections().forEach(section -> values.put(section.key(), section.title() + "\n" + section.text()));
            Matcher matcher = Pattern.compile("\\{\\{([^{}]+)}}").matcher(template.body());
            body.append(paragraph(matcher.replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), "")))));
        } else if (report.sections().isEmpty()) {
            body.append(paragraph(report.text()));
        } else {
            int number = 1;
            for (var section : report.sections()) {
                body.append(section(style, section, number++, accent));
            }
        }
        String period = isReport ? report.date() : "任务提醒";
        String eyebrow = "EDITORIAL".equals(style) ? "REPORT  /  " : "LEDGER".equals(style) ? "BUTVAN  ·  " : "BUTVAN AGENT  /  ";
        return "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:" + background + ";color:#253149;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Arial,sans-serif'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td align='center' style='padding:32px 12px'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='max-width:640px;background:#ffffff;border-radius:"
                + ("EDITORIAL".equals(style) ? "0" : "18") + "px'><tr><td style='padding:36px 32px'>"
                + "<p style='margin:0 0 22px;font-size:11px;font-weight:700;letter-spacing:2px;color:" + accent + "'>" + eyebrow + escape(spec.reportLabel()).toUpperCase(Locale.ROOT) + "</p>"
                + "<h1 style='margin:0 0 10px;font-size:30px;line-height:1.35;color:#1e293b'>" + escape(heading) + "</h1>"
                + "<p style='margin:0 0 8px;font-size:14px;color:#69758b;line-height:1.7'>" + escape(intro) + "</p>"
                + "<p style='margin:0 0 28px;font-size:12px;color:" + accent + ";line-height:1.7'>" + escape(period) + "</p>"
                + body + (report.partial() ? "<p style='padding:12px;background:#fff4e5;color:#8c5b18;font-size:13px'>部分来源暂不可用，已标注在相应栏目。</p>" : "")
                + "<p style='margin:28px 0 0;border-top:1px solid #e5eaf1;padding-top:20px;font-size:12px;color:#8390a2;line-height:1.8'>"
                + (report.generatedAt().isBlank() ? "" : "生成于 " + escape(report.generatedAt()) + "<br>")
                + escape(footer) + "</p></td></tr></table></td></tr></table></body></html>";
    }
    private static String section(String style, TaskReportBuilder.Section section, int number, String accent) {
        String card = "DIGEST".equals(style) ? "background:#f3f7fd;border-radius:12px;padding:22px" :
                "LEDGER".equals(style) ? "background:#faf7f0;border-left:3px solid " + accent + ";padding:20px" :
                        "border-top:2px solid #27334a;padding:20px 0";
        StringBuilder html = new StringBuilder("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin:0 0 16px'><tr><td style='" + card + "'>")
                .append("<p style='margin:0 0 12px;font-size:11px;letter-spacing:1px;color:").append(accent).append("'>")
                .append(String.format(Locale.ROOT, "%02d", number)).append(" / ").append(escape(section.title())).append("</p>");
        if (!section.metrics().isEmpty()) {
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='5' style='border-collapse:collapse;font-size:13px'>")
                .append("<tr style='color:#7d899c'><th align='left'>指标</th><th align='right'>本期</th><th align='right'>上期</th><th align='right'>变化</th></tr>");
            for (var metric : section.metrics()) html.append("<tr style='border-top:1px solid #dde4ed'><td>")
                .append(escape(metric.label())).append("</td><td align='right' style='font-weight:700'>")
                .append(escape(metric.current())).append("</td><td align='right'>")
                .append(escape(metric.previous())).append("</td><td align='right' style='color:").append(accent).append("'>")
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
