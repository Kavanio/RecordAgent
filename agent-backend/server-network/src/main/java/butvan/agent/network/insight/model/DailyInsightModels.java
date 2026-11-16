package butvan.agent.network.insight.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

/** Slash Command 等读取方使用的跨领域每日洞察模型。 */
public final class DailyInsightModels {
    private DailyInsightModels() {
    }

    /** 个人月度统计总览；所有字段均由各领域实时事实聚合计算。 */
    public record ProfileOverview(
            YearMonth month,
            long totalAssetsInCents,
            long totalExpenseInCents,
            long totalRecordMinutes,
            int documentCount,
            long documentWordCount,
            int monthlyCompletedTodos,
            int monthlyTodoCount,
            Double assetsGrowthRate,
            Double expenseGrowthRate,
            Double recordMinutesGrowthRate) {
    }

    /** 指定自然日的确定性活动汇总。 */
    public record DailyInsight(
            LocalDate date,
            TodoSummary todos,
            int scheduleCount,
            RecordSummary records,
            FinanceSummary finance,
            StudySummary study) {
    }

    public record TodoSummary(int total, int completed, int pending) {
    }

    public record RecordSummary(int createdCount) {
    }

    public record FinanceSummary(int expenseCount, BigDecimal expenseTotal,
                                 Map<String, BigDecimal> expenseCategories) {
    }

    public record StudySummary(long durationSeconds, int sessionCount) {
    }
}
