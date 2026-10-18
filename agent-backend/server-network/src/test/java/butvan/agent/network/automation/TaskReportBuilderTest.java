package butvan.agent.network.automation;

import butvan.agent.network.automation.model.TaskSpec;
import butvan.agent.network.automation.service.TaskReportBuilder;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.study.service.StudyService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
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
}
