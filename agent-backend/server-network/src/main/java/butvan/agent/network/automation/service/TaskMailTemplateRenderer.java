package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.MailTemplate;
import butvan.agent.network.automation.model.TaskSpec;
import org.springframework.web.util.HtmlUtils;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Pattern;

/** 使用邮件兼容的表格与内联样式渲染；执行时冻结 HTML，重发复用原样式和数据。 */
public final class TaskMailTemplateRenderer {
    private TaskMailTemplateRenderer() {}
    /** 模板替换只扫描模板本身，业务文本中的占位符不会被二次展开。 */
    public static String render(TaskSpec spec, TaskReportBuilder.Report report) {
        boolean dailyReport = "DAILY_REPORT".equals(spec.kind());
        var t = dailyReport ? spec.mailTemplate() : MailTemplate.defaults();
        t.validate();
        boolean letter = "LETTER".equals(t.style());
        boolean minimal = "MINIMAL".equals(t.style());
        String accent = "CUSTOM".equals(t.style()) ? t.accent() : letter ? "#986b42" : "#39756b";
        String background = letter ? "#f6f0e6" : minimal ? "#ffffff" : "#f1f5f4";
        String heading = dailyReport ? ("CUSTOM".equals(t.style()) ? t.heading() : "今日生活简报") : spec.title();
        StringBuilder sections = new StringBuilder();
        if ("CUSTOM".equals(t.style())) {
            Map<String, String> values = new HashMap<>(Map.of("title", spec.title(), "date", report.date(),
                    "generatedAt", report.generatedAt(), "content", report.text(), "expense", "", "todo", "", "study", ""));
            report.sections().forEach(s -> values.put(s.key(), s.title() + "\n" + s.text()));
            var matcher = Pattern.compile("\\{\\{([^{}]+)}}").matcher(t.body());
            String rendered = matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(values.getOrDefault(match.group(1), "")));
            sections.append(block("", rendered, false));
        } else if (report.sections().isEmpty()) {
            sections.append(block("", report.text(), false));
        } else for (var section : report.sections()) {
            sections.append(block(section.title(), section.text(), !minimal));
        }
        String intro = !dailyReport ? "" : "CUSTOM".equals(t.style()) ? t.introduction() : "把一天的点滴，整理成值得回看的记录。";
        String footer = "CUSTOM".equals(t.style()) ? t.footer() : "来自 ButvanAgent · 为每一天留下一点记录";
        return "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='margin:0;background:" + background + ";color:#26332f;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Arial,sans-serif'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td align='center' style='padding:24px 12px'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='max-width:600px;background:#ffffff;border-radius:16px'><tr><td style='padding:32px 24px'>"
                + "<p style='margin:0 0 12px;font-size:12px;letter-spacing:2px;color:" + accent + "'>" + (dailyReport ? "每日记录 · " + escape(report.date()) : "任务提醒") + "</p>"
                + "<h1 style='margin:0 0 14px;font-size:28px;line-height:1.4;color:" + accent + "'>" + escape(heading) + "</h1>"
                + "<p style='margin:0 0 24px;color:#718078;font-size:14px;line-height:1.8'>" + escape(intro) + "</p>"
                + sections + (report.partial() ? "<p style='color:#986b42'>部分栏目暂不可用，详情见上方说明。</p>" : "")
                + "<p style='font-size:12px;line-height:1.8;color:#7a847f;border-top:1px solid #e8ece9;padding-top:20px'>"
                + (report.generatedAt().isBlank() ? "" : "截至 " + escape(report.generatedAt()) + " 的已记录数据，非全天最终结算。<br>")
                + escape(footer) + "</p></td></tr></table></td></tr></table></body></html>";
    }
    private static String block(String title, String text, boolean card) {
        return "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-bottom:16px'><tr><td style='padding:"
                + (card ? "20px;background:#f6f8f7;border-radius:12px" : "12px 0") + "'>"
                + (title.isBlank() ? "" : "<h2 style='font-size:16px;margin:0 0 10px'>" + escape(title) + "</h2>")
                + "<div style='font-size:15px;line-height:1.9;overflow-wrap:anywhere;word-break:break-word'>" + escape(text).replace("\n", "<br>") + "</div></td></tr></table>";
    }
    private static String escape(String text) { return HtmlUtils.htmlEscape(text); }
}
