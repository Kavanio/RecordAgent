package com.butvan.recordagent.dto.overview;

import java.time.LocalDate;
import java.util.List;

/** 首页七日趋势与个人累计统计契约。 */
public final class OverviewDtos {
    private OverviewDtos() {}

    public record TrendDay(LocalDate date, long expenseInCents, long focusMinutes) {}
    public record DayTrend(LocalDate date, List<TrendDay> trend) {}
    public record Profile(String month, long totalAssetsInCents, long totalExpenseInCents,
            long totalRecordMinutes, long documentCount, long documentWordCount,
            long monthlyCompletedTodos, long monthlyTodoCount,
            Double assetsGrowthRate, Double expenseGrowthRate, Double recordMinutesGrowthRate) {}
}
