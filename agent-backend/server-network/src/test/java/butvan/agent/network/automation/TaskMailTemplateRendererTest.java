package butvan.agent.network.automation;

import butvan.agent.network.automation.model.*;
import butvan.agent.network.automation.service.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** 验证邮件布局差异、内容安全与自定义替换，不依赖真实 SMTP。 */
class TaskMailTemplateRendererTest {
    private TaskSpec spec(MailTemplate template) {
        return new TaskSpec("日报", "", "DAILY_REPORT", "DAILY", "Asia/Shanghai", "20:30", null,
                127, 45, 5, "00:00", "00:00", true, true, false, false, true, true, true, template);
    }
    private final TaskReportBuilder.Report report = new TaskReportBuilder.Report("报告", false,
            List.of(new TaskReportBuilder.Section("expense", "今日支出", "CNY 12.68"),
                    new TaskReportBuilder.Section("todo", "待办进度", "<img src=x onerror=alert(1)> {{study}}")),
            "2026-09-23", "2026-09-23 18:00（Asia/Shanghai）");

    @Test void presetsHaveDifferentLayoutsAndReadableValues() {
        var outputs = Set.of("DIGEST", "EDITORIAL", "LEDGER", "RECEIPT").stream().map(style ->
                TaskMailTemplateRenderer.render(spec(new MailTemplate(style, null, null, null, null, null)), report))
                .collect(Collectors.toSet());
        assertEquals(4, outputs.size());
        for (String html : outputs) {
            assertTrue(html.contains("CNY 12.68"));
            assertTrue(html.contains("2026-09-23 18:00"));
            assertFalse(html.contains("<img"));
            assertTrue(html.contains("&lt;img"));
            assertFalse(html.contains("<pre"));
        }
    }
    @Test void receiptUsesEmailSafeTicketStructure() {
        var compared = new TaskReportBuilder.Report("报告", false,
                List.of(new TaskReportBuilder.Section("expense", "支出概览", "CNY：本期 12.68 · 上期 8.00 · +4.68",
                        List.of(new TaskReportBuilder.Metric("CNY", "12.68", "8.00", "+4.68（+58.5%）")), "按币种分别统计。")),
                "2026-09-23 · 对比 2026-09-22", "2026-09-24 08:30（Asia/Shanghai）");
        var html = TaskMailTemplateRenderer.render(spec(new MailTemplate("RECEIPT", null, null, null, null, null)), compared);
        assertTrue(html.contains("本期日报已生成"));
        assertTrue(html.contains("本期周期"));
        assertTrue(html.contains("对比周期"));
        assertTrue(html.contains("报告归档码"));
        assertTrue(html.contains("2px dashed #dedede"));
        assertFalse(html.contains("@keyframes"));
        assertFalse(html.contains("<script"));
    }
    @Test void customTextIsEscapedAndSubstitutionIsSinglePass() {
        var template = new MailTemplate("CUSTOM", "#123456", "<标题>", "开场", "落款", "{{date}}\n{{todo}}\n{{expense}}\n{{study}}");
        var html = TaskMailTemplateRenderer.render(spec(template), report);
        assertTrue(html.contains("#123456"));
        assertTrue(html.contains("&lt;标题&gt;"));
        assertTrue(html.contains("{{study}}")); // 业务数据中的标记不展开。
        assertTrue(html.indexOf("待办进度") < html.indexOf("今日支出"));
        assertFalse(html.contains("<img"));
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
    @Test void invalidColorAndUnknownPlaceholdersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MailTemplate("CUSTOM", "red;background:url(x)", null, null, null, "正文").validate());
        assertThrows(IllegalArgumentException.class, () -> new MailTemplate("CUSTOM", null, null, null, null, "{{password}}").validate());
        assertThrows(IllegalArgumentException.class, () -> new MailTemplate("CUSTOM", null, null, null, null, " ").validate());
    }
}
