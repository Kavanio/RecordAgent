package butvan.agent.network.automation;

import butvan.agent.network.automation.model.*;
import butvan.agent.network.automation.service.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.web.util.HtmlUtils.htmlEscape;

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

    @Test void digestRestoresPaperLayoutWithFewRulesAndEscapesContent() {
        var html = TaskMailTemplateRenderer.render(spec(MailTemplate.defaults()), report);
        assertTrue(html.contains("background:#ebebe9"));
        assertTrue(html.contains("max-width:680px"));
        assertTrue(html.contains("butvan."));
        assertTrue(html.contains("A day, recorded."));
        assertTrue(html.contains("把今天，留在这里。"));
        assertTrue(html.contains("收支概况"));
        assertTrue(html.contains("CNY 12.68"));
        assertTrue(html.contains("2026-09-23 18:00"));
        assertFalse(html.contains("background:#f3f7fd"));
        assertFalse(html.contains("<hr"));
        assertFalse(html.contains("<img"));
        assertTrue(html.contains("&lt;img"));
        assertFalse(html.contains("<pre"));
    }
    @Test void paperLayoutShowsStructuredAssetsAndTimeline() {
        var structured = new TaskReportBuilder.Report("日报", false,
                List.of(new TaskReportBuilder.Section("expense", "资产与收支", ""),
                        new TaskReportBuilder.Section("todo", "待办进度", "")),
                "2026-09-23 · 对比 2026-09-22", "2026-09-23 18:00（Asia/Shanghai）",
                List.of(new TaskReportBuilder.AccountSummary("微信零钱", "CNY", "577.84", "5.00", "10.00")),
                List.of(new TaskReportBuilder.TimelineItem("16:57", "expense", "交通 · 地铁回家", "微信零钱", "− ¥10.00")));
        var html = TaskMailTemplateRenderer.render(spec(MailTemplate.defaults()), structured);
        assertTrue(html.contains("TOTAL ASSETS / 资产余额"));
        assertTrue(html.contains("资产明细"));
        assertTrue(html.contains("收支概况"));
        assertTrue(html.contains("一天的轨迹"));
        assertTrue(html.contains("微信零钱"));
        assertTrue(html.contains("16:57"));
        assertTrue(html.contains("10.00"));
        assertTrue(html.contains("color:#365f8b"));
        assertTrue(html.contains("font-weight:650;color:#467460"));
        assertTrue(html.contains("font-weight:650;color:#ac685c"));
        assertFalse(html.contains("padding:12px 0;border-bottom:1px solid #ededeb"));
    }

    @Test void progressShowsCompletedTodoNamesWithoutDuplicatingPercentAndKeepsTimelineCategories() {
        var structured = new TaskReportBuilder.Report("日报", false,
                List.of(new TaskReportBuilder.Section("todo", "待办进度", "", List.of(
                                new TaskReportBuilder.Metric("应办（项）", "1", "1", "持平"),
                                new TaskReportBuilder.Metric("已完成（项）", "1", "0", "+1"),
                                new TaskReportBuilder.Metric("完成率", "100%", "0%", "+100 个百分点")),
                                "已完成\n• 2026-09-23 · 整理项目计划\n"),
                        new TaskReportBuilder.Section("study", "学习时光", "", List.of(
                                new TaskReportBuilder.Metric("学习时长（分钟）", "120", "60", "+60"),
                                new TaskReportBuilder.Metric("学习记录（条）", "1", "1", "持平")), "按任务时区统计周期内的学习记录。")),
                "2026-09-23 · 对比 2026-09-22", "2026-09-23 18:00（Asia/Shanghai）", List.of(),
                List.of(new TaskReportBuilder.TimelineItem("08:00", "study", "完成 Java 练习", "后端 · 家中", "08:00 — 10:00 · 2h 0m"),
                        new TaskReportBuilder.TimelineItem("10:14", "todo", "整理项目计划", "待办已勾选完成", ""),
                        new TaskReportBuilder.TimelineItem("11:00", "other", "余额调增", "微信零钱", "+ ¥10.00")));

        var html = TaskMailTemplateRenderer.render(spec(MailTemplate.defaults()), structured);

        assertTrue(html.contains("完成率</div><div style='margin-top:10px;font-size:24px;letter-spacing:-1px;font-weight:520;font-variant-numeric:tabular-nums;color:#20211f'>100%</div>"));
        assertTrue(html.contains("整理项目计划"));
        assertTrue(html.contains("2026-09-23"));
        assertTrue(html.contains("完成 Java 练习"));
        assertTrue(html.contains("2h 0m"));
        assertTrue(html.contains("待办完成"));
        assertTrue(html.contains("整理项目计划"));
        assertTrue(html.contains("10:14"));
        assertTrue(html.contains("其他变动"));
    }
    /** 学习时段属于事件元信息，不应像金额一样挤占标题右侧的固定宽度。 */
    @Test void timelineKeepsStudyTimingBelowFullWidthTitle() {
        var html = renderTimeline(List.of(new TaskReportBuilder.TimelineItem(
                "08:16", "study", "看八股文", "八股文", "08:16 — 09:35 · 1h 19m")));
        assertTrue(html.contains("class='timeline-meta'"));
        assertTrue(html.indexOf("看八股文") < html.indexOf(htmlEscape("08:16 — 09:35 · 1h 19m")));
        assertFalse(html.contains("class='timeline-amount'"));
        assertFalse(html.contains("width='132'"));
        assertFalse(html.contains("●"), "分类标签已说明事件类型，不再重复堆叠图例和圆点");
        assertFalse(html.contains("border-left:2px"), "仅保留一条浅色时间轴，不再为每条事件增加彩色竖线");
    }

    @Test void timelineAlignsFinancialAmountsAndStacksOnNarrowScreens() {
        var html = renderTimeline(List.of(
                new TaskReportBuilder.TimelineItem("11:19", "expense", "餐饮 · 午饭", "微信余额", "− ¥10.00"),
                new TaskReportBuilder.TimelineItem("12:00", "income", "工资", "银行卡", "+ ¥12,345.67")));
        assertEquals(2, html.split("class='timeline-amount'", -1).length - 1);
        assertTrue(html.contains(htmlEscape("− ¥10.00")));
        assertTrue(html.contains(htmlEscape("+ ¥12,345.67")));
        assertTrue(html.contains("@media(max-width:420px)"));
        assertTrue(html.contains(".timeline-title,.timeline-amount{display:block!important;width:auto!important}"));
        assertTrue(html.contains("text-align:left!important"));
        assertTrue(html.contains("overflow-wrap:anywhere"));
    }

    @Test void timelineEscapesLongTextAndDoesNotReserveEmptyAmountOrDetailRows() {
        var title = "很长的待办名称".repeat(25) + "<img src=x onerror=alert(1)>";
        var html = renderTimeline(List.of(new TaskReportBuilder.TimelineItem(null, "todo", title, "", "")));
        assertTrue(html.contains("很长的待办名称".repeat(25)));
        assertTrue(html.contains("&lt;img"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("class='timeline-amount'"));
        assertFalse(html.contains("class='timeline-meta'"));
        assertFalse(html.contains("class='timeline-detail'"));
        assertTrue(html.contains(">" + htmlEscape("—") + "</td>"));
    }

    private String renderTimeline(List<TaskReportBuilder.TimelineItem> items) {
        return TaskMailTemplateRenderer.render(spec(MailTemplate.defaults()), new TaskReportBuilder.Report(
                "日报", false, List.of(), "2026-09-23", "2026-09-23 18:00（Asia/Shanghai）", List.of(), items));
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
        assertTrue(html.contains("本期支出"));
        assertTrue(html.contains("上期支出"));
        assertTrue(html.contains("+58.5%"));
        assertTrue(html.contains("CNY"));
    }
    @Test void unknownStylesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MailTemplate("FUTURE_UNKNOWN", null, null, null, null, null).validate());
    }
}
