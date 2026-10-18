package butvan.agent.network.automation;

import butvan.agent.network.automation.model.*;
import butvan.agent.network.automation.service.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 验证蓝调摘要布局、旧模板兼容与内容安全，不依赖真实 SMTP。 */
class TaskMailTemplateRendererTest {
    private TaskSpec spec(MailTemplate template) {
        return new TaskSpec("日报", "", "DAILY_REPORT", "DAILY", "Asia/Shanghai", "20:30", null,
                127, 45, 5, "00:00", "00:00", true, true, false, false, true, true, true, template);
    }
    private final TaskReportBuilder.Report report = new TaskReportBuilder.Report("报告", false,
            List.of(new TaskReportBuilder.Section("expense", "今日支出", "CNY 12.68"),
                    new TaskReportBuilder.Section("todo", "待办进度", "<img src=x onerror=alert(1)> {{study}}")),
            "2026-09-23", "2026-09-23 18:00（Asia/Shanghai）");

    @Test void digestUsesEditorialLayoutWithFewRulesAndEscapesContent() {
        var html = TaskMailTemplateRenderer.render(spec(MailTemplate.defaults()), report);
        assertTrue(html.contains("background:#ebebe9"));
        assertTrue(html.contains("把今天，留在这里。"));
        assertTrue(html.contains("今日支出"));
        assertTrue(html.contains("CNY 12.68"));
        assertTrue(html.contains("2026-09-23 18:00"));
        assertFalse(html.contains("border-bottom"));
        assertFalse(html.contains("border-top"));
        assertFalse(html.contains("<hr"));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("&lt;img"));
        assertFalse(html.contains("<pre"));
    }
    @Test void allPreviouslySavedStylesRenderAsDigest() {
        var styles = List.of("CARDS", "MINIMAL", "LETTER", "EDITORIAL", "LEDGER", "RECEIPT", "CUSTOM");
        for (String style : styles) {
            var html = TaskMailTemplateRenderer.render(spec(new MailTemplate(style, "#123456", "旧标题", "旧开场", "旧页脚", "旧正文")), report);
            assertTrue(html.contains("background:#ebebe9"));
            assertTrue(html.contains("把今天，留在这里。"));
            assertFalse(html.contains("旧标题"));
            assertFalse(html.contains("旧正文"));
        }
    }
    @Test void presetRendersCurrentPreviousAndChangeAsColumns() {
        var compared = new TaskReportBuilder.Report("报告", false,
                List.of(new TaskReportBuilder.Section("expense", "支出概览", "CNY：本期 12.68 · 上期 8.00 · +4.68",
                        List.of(new TaskReportBuilder.Metric("CNY", "12.68", "8.00", "+4.68（+58.5%）")), "按币种分别统计。")),
                "2026-09-23 · 对比 2026-09-22", "2026-09-24 08:30（Asia/Shanghai）");
        var html = TaskMailTemplateRenderer.render(spec(MailTemplate.defaults()), compared);
        assertTrue(html.contains("<th align='right'>本期</th>"));
        assertTrue(html.contains("<th align='right'>上期</th>"));
        assertTrue(html.contains("+58.5%"));
        assertTrue(html.contains("按币种分别统计"));
    }
    @Test void unknownStylesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MailTemplate("FUTURE_UNKNOWN", null, null, null, null, null).validate());
    }
}
