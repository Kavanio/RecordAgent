package butvan.agent.network.automation;

import butvan.agent.network.automation.model.TaskSpec;
import butvan.agent.network.automation.service.TaskReportBuilder;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.model.DailyEventModels.DailyDay;
import butvan.agent.network.daily.model.DailyEventModels.DailyEvent;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.study.service.StudyService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 周期报告将无记录与数据读取失败区分，不把不可用数据写成零。 */
class TaskReportBuilderTest {
    private final DailyEventService daily = mock(DailyEventService.class);
    private final ExpenseAnalyticsService expense = mock(ExpenseAnalyticsService.class);
    private final StudyService study = mock(StudyService.class);
    private final FinanceService finance = mock(FinanceService.class);
    private final TaskReportBuilder builder = new TaskReportBuilder(daily, expense, study, finance);
    private final TaskSpec spec = new TaskSpec("日报", "", "DAILY_REPORT", "DAILY", "Asia/Shanghai",
            "20:30", null, 127, 45, 5, "00:00", "00:00", true, false, false, false, true, false, false);
    @Test void noRecordedExpensesAreExplicitlyEmpty() {
        when(expense.reportExpenses(anyString(), any(), any())).thenReturn(List.of());
        when(finance.getDailyAssetData(anyString(), any())).thenReturn(new FinanceService.DailyAssetData(List.of(), List.of()));
        var result = builder.build("owner", spec, Instant.parse("2026-09-23T12:30:00Z"), Instant.parse("2026-09-23T12:30:00Z"));
        assertFalse(result.partial());
        assertTrue(result.text().contains("支出：无记录"));
        assertTrue(result.text().contains("2026-09-23"));
        assertTrue(result.text().contains("对比上期：2026-09-22"));
    }
    @Test void unavailableExpenseSourceFailsInsteadOfProducingZero() {
        when(expense.reportExpenses(anyString(), any(), any())).thenThrow(new IllegalStateException("source unavailable"));
        when(finance.getReportTransactions(anyString(), any(), any())).thenThrow(new IllegalStateException("source unavailable"));
        assertThrows(IllegalStateException.class, () -> builder.build("owner", spec,
                Instant.parse("2026-09-23T12:30:00Z"), Instant.parse("2026-09-23T12:30:00Z")));
    }

    @Test void monthlyReportIncludesRealIncomeNetAndDayNormalizedComparisons() {
        var september = LocalDate.of(2026, 9, 1);
        when(expense.reportExpenses(eq("owner"), any(), any())).thenAnswer(call -> {
            LocalDate from = call.getArgument(1);
            return List.of(new ExpenseAnalyticsService.ExpenseEntry("e", from, java.time.LocalTime.NOON,
                    "餐饮", "订单", new java.math.BigDecimal(from.equals(september) ? "100" : "50"), "CNY", "finance", Instant.EPOCH));
        });
        when(finance.getReportTransactions(eq("owner"), any(), any())).thenAnswer(call -> {
            LocalDate from = call.getArgument(1);
            return List.of(new butvan.agent.network.finance.model.FinanceModels.FinanceTransaction(
                    "income", "a", "银行卡", from, java.time.LocalTime.NOON, "income", "工资", "",
                    new java.math.BigDecimal(from.equals(september) ? "200" : "100"), "CNY", "manual", Instant.EPOCH),
                    new butvan.agent.network.finance.model.FinanceModels.FinanceTransaction(
                            "transfer", "a", "银行卡", from, java.time.LocalTime.NOON, "transfer_in", "划账", "",
                            new java.math.BigDecimal("9999"), "CNY", "manual", Instant.EPOCH));
        });
        TaskSpec monthly = new TaskSpec("月报", "", "MONTHLY_REPORT", "MONTHLY", "Asia/Shanghai",
                "20:30", null, 127, 45, 5, "00:00", "00:00", true, false, false, false, true, false, false);
        var result = builder.build("owner", monthly, Instant.parse("2026-10-01T12:30:00Z"), Instant.parse("2026-10-01T12:30:00Z"));
        var income = result.sections().stream().filter(s -> s.key().equals("income")).findFirst().orElseThrow();
        assertTrue(income.metrics().stream().anyMatch(m -> m.label().equals("CNY") && m.current().equals("200.00") && m.previous().equals("100.00")));
        assertTrue(income.metrics().stream().anyMatch(m -> m.label().endsWith("日均") && m.current().equals("6.67") && m.previous().equals("3.23")));
        var net = result.sections().stream().filter(s -> s.key().equals("net")).findFirst().orElseThrow();
        assertEquals("100.00", net.metrics().getFirst().current());
        assertEquals("50.00", net.metrics().getFirst().previous());
        assertFalse(income.detail().contains("9999"));
        assertFalse(result.partial());
    }

    @Test void availableExpensesSurviveIncomeFailureWithoutInventingNetBalance() {
        when(expense.reportExpenses(anyString(), any(), any())).thenReturn(List.of());
        when(finance.getReportTransactions(anyString(), any(), any())).thenThrow(new IllegalStateException("unavailable"));
        when(finance.getDailyAssetData(anyString(), any())).thenReturn(new FinanceService.DailyAssetData(List.of(), List.of()));
        var result = builder.build("owner", spec, Instant.parse("2026-09-23T12:30:00Z"), Instant.parse("2026-09-23T12:30:00Z"));
        assertTrue(result.partial());
        assertTrue(result.sections().stream().filter(s -> s.key().equals("net")).findFirst().orElseThrow()
                .metrics().stream().allMatch(m -> m.current().equals("不可用")));
    }

    @Test void weeklyTodoSummaryCountsAllOccurrencesButLimitsNames() {
        when(daily.getDay(eq("owner"), any())).thenAnswer(call -> {
            LocalDate date = call.getArgument(1);
            var events = java.util.stream.IntStream.range(0, 10).mapToObj(index -> new DailyEvent(
                    "todo-" + index, date, "todo", "事项" + index, "manual", "confirmed", 1, Instant.EPOCH, Instant.EPOCH,
                    new TodoDetails(null, "normal", false, "daily", null, null, null))).toList();
            return new DailyDay(date, events);
        });
        TaskSpec weekly = new TaskSpec("周报", "", "WEEKLY_REPORT", "WEEKLY", "Asia/Shanghai",
                "20:30", null, 1, 45, 5, "00:00", "00:00", true, false, false, false, false, true, false);
        var result = builder.build("owner", weekly, Instant.parse("2026-09-28T12:30:00Z"), Instant.parse("2026-09-28T12:30:00Z"));
        var todo = result.sections().stream().filter(s -> s.key().equals("todo")).findFirst().orElseThrow();
        assertEquals(3, todo.detail().lines().filter(line -> line.startsWith("• ")).count());
        assertTrue(todo.detail().contains("其余请在日历查看"));
        assertTrue(todo.metrics().stream().anyMatch(m -> m.label().equals("应办（项）") && m.current().equals("70")));
    }

    @Test void completedTodosKeepTheirDateAndTitleInReportDetails() {
        LocalDate date = LocalDate.parse("2026-09-23");
        DailyEvent completed = new DailyEvent("todo-1", date, "todo", "整理项目计划", "manual", "confirmed", 1,
                Instant.parse("2026-09-23T08:00:00Z"), Instant.parse("2026-09-23T08:00:00Z"),
                new TodoDetails(null, "normal", true, "daily", null, null, Instant.parse("2026-09-23T02:14:00Z")));
        when(daily.getDay("owner", date)).thenReturn(new DailyDay(date, List.of(completed)));
        when(daily.getDay("owner", date.minusDays(1))).thenReturn(new DailyDay(date.minusDays(1), List.of()));
        TaskSpec todoSpec = new TaskSpec("日报", "", "DAILY_REPORT", "DAILY", "Asia/Shanghai",
                "20:30", null, 127, 45, 5, "00:00", "00:00", true, false, false, false, false, true, false);

        var result = builder.build("owner", todoSpec, Instant.parse("2026-09-23T12:30:00Z"), Instant.parse("2026-09-23T12:30:00Z"));

        var todo = result.sections().stream().filter(section -> "todo".equals(section.key())).findFirst().orElseThrow();
        assertTrue(todo.detail().contains("已完成\n• 2026-09-23 10:14 · 整理项目计划"));
        assertEquals(List.of(new TaskReportBuilder.TimelineItem("10:14", "todo", "整理项目计划", "待办已勾选完成", "")),
                result.timeline());
        assertTrue(todo.metrics().stream().anyMatch(metric -> "完成率".equals(metric.label()) && "100%".equals(metric.current())));
    }
}
