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
        assertThrows(IllegalStateException.class, () -> builder.build("owner", spec,
                Instant.parse("2026-09-23T12:30:00Z"), Instant.parse("2026-09-23T12:30:00Z")));
    }

    @Test void completedTodosKeepTheirDateAndTitleInReportDetails() {
        LocalDate date = LocalDate.parse("2026-09-23");
        DailyEvent completed = new DailyEvent("todo-1", date, "todo", "整理项目计划", "manual", "confirmed", 1,
                Instant.parse("2026-09-23T08:00:00Z"), Instant.parse("2026-09-23T08:00:00Z"),
                new TodoDetails(null, "normal", true, "daily", null, null));
        when(daily.getDay("owner", date)).thenReturn(new DailyDay(date, List.of(completed)));
        when(daily.getDay("owner", date.minusDays(1))).thenReturn(new DailyDay(date.minusDays(1), List.of()));
        TaskSpec todoSpec = new TaskSpec("日报", "", "DAILY_REPORT", "DAILY", "Asia/Shanghai",
                "20:30", null, 127, 45, 5, "00:00", "00:00", true, false, false, false, false, true, false);

        var result = builder.build("owner", todoSpec, Instant.parse("2026-09-23T12:30:00Z"), Instant.parse("2026-09-23T12:30:00Z"));

        var todo = result.sections().stream().filter(section -> "todo".equals(section.key())).findFirst().orElseThrow();
        assertTrue(todo.detail().contains("已完成\n• 2026-09-23 · 整理项目计划"));
        assertTrue(todo.metrics().stream().anyMatch(metric -> "完成率".equals(metric.label()) && "100%".equals(metric.current())));
    }
}
