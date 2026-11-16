package butvan.agent.network.insight.dto;

import butvan.agent.network.insight.model.DailyInsightModels.DailyInsight;
import butvan.agent.network.insight.model.DailyInsightModels.ProfileOverview;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** 每日洞察 HTTP 响应及领域映射。 */
public final class DailyInsightDtos {
    private DailyInsightDtos() {
    }

    /** 个人月度统计总览 HTTP 响应。 */
    public record ProfileOverviewResponse(
            String month,
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

    public record DailyInsightResponse(
            LocalDate date,
            TodoSummaryResponse todos,
            int scheduleCount,
            RecordSummaryResponse records,
            FinanceSummaryResponse finance,
            StudySummaryResponse study) {
    }

    public record TodoSummaryResponse(int total, int completed, int pending) {
    }

    public record RecordSummaryResponse(int createdCount) {
    }

    public record FinanceSummaryResponse(int expenseCount, BigDecimal expenseTotal,
                                         Map<String, BigDecimal> expenseCategories) {
    }

    public record StudySummaryResponse(long durationSeconds, int sessionCount) {
    }

    public static DailyInsightResponse from(DailyInsight insight) {
        return new DailyInsightResponse(insight.date(),
                new TodoSummaryResponse(insight.todos().total(), insight.todos().completed(), insight.todos().pending()),
                insight.scheduleCount(),
                new RecordSummaryResponse(insight.records().createdCount()),
                new FinanceSummaryResponse(insight.finance().expenseCount(), insight.finance().expenseTotal(),
                        insight.finance().expenseCategories()),
                new StudySummaryResponse(insight.study().durationSeconds(), insight.study().sessionCount()));
    }

    /** 将个人月度统计总览转换为 HTTP 响应 DTO。 */
    public static ProfileOverviewResponse from(ProfileOverview overview) {
        return new ProfileOverviewResponse(
                overview.month().toString(),
                overview.totalAssetsInCents(),
                overview.totalExpenseInCents(),
                overview.totalRecordMinutes(),
                overview.documentCount(),
                overview.documentWordCount(),
                overview.monthlyCompletedTodos(),
                overview.monthlyTodoCount(),
                overview.assetsGrowthRate(),
                overview.expenseGrowthRate(),
                overview.recordMinutesGrowthRate());
    }
}
