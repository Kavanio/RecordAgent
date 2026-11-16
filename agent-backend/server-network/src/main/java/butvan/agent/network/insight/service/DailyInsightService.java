package butvan.agent.network.insight.service;

import butvan.agent.network.daily.model.DailyEventModels.DailyDaySummary;
import butvan.agent.network.daily.model.DailyEventModels.DailyEvent;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.insight.model.DailyInsightModels.DailyInsight;
import butvan.agent.network.insight.model.DailyInsightModels.FinanceSummary;
import butvan.agent.network.insight.model.DailyInsightModels.ProfileOverview;
import butvan.agent.network.insight.model.DailyInsightModels.RecordSummary;
import butvan.agent.network.insight.model.DailyInsightModels.StudySummary;
import butvan.agent.network.insight.model.DailyInsightModels.TodoSummary;
import butvan.agent.network.record.model.RecordModels.RecordStats;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.study.service.StudyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * 在一个只读入口内组合日历、资料、财务与学习的既有统计口径。
 *
 * <p>调用方只需提供用户、自然日和时区，不需要了解各领域的查询细节。</p>
 */
@Service
@RequiredArgsConstructor
public class DailyInsightService {
    private static final BigDecimal CENTS_SCALE = new BigDecimal("100");

    private final DailyEventService dailyEventService;
    private final RecordService recordService;
    private final ExpenseAnalyticsService expenseAnalyticsService;
    private final FinanceService financeService;
    private final StudyService studyService;

    /** 返回指定自然日的活动汇总，不调用模型且不写入业务数据。 */
    @Transactional(readOnly = true)
    public DailyInsight getDailyInsight(String ownerId, LocalDate date, ZoneId timezone) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
        if (date == null || timezone == null) throw new IllegalArgumentException("日期与时区不能为空");

        List<DailyEvent> events = dailyEventService.getDay(ownerId, date).events();
        List<TodoDetails> todos = events.stream()
                .filter(event -> "todo".equals(event.eventType()))
                .map(DailyEvent::details)
                .filter(TodoDetails.class::isInstance)
                .map(TodoDetails.class::cast)
                .toList();
        int completedTodos = (int) todos.stream().filter(TodoDetails::completed).count();
        int scheduleCount = (int) events.stream().filter(event -> "schedule".equals(event.eventType())).count();
        int expenseCount = (int) events.stream().filter(event -> "expense".equals(event.eventType())).count();

        int recordCount = recordService.summarizeDays(ownerId, date, date).stream()
                .findFirst().map(summary -> summary.count()).orElse(0);
        var expenseDay = expenseAnalyticsService.analyze(ownerId, date, date).days().getFirst();
        var study = studyService.getStatistics(ownerId, date, date, timezone);

        return new DailyInsight(date,
                new TodoSummary(todos.size(), completedTodos, todos.size() - completedTodos),
                scheduleCount,
                new RecordSummary(recordCount),
                new FinanceSummary(expenseCount, expenseDay.total(), expenseDay.categories()),
                new StudySummary(study.totalDurationSeconds(), study.sessionCount()));
    }

    /** 返回指定自然月的个人统计总览与月环比，所有数值按领域服务真实口径聚合。 */
    @Transactional(readOnly = true)
    public ProfileOverview getProfileOverview(String ownerId, YearMonth month, ZoneId timezone) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
        if (month == null || timezone == null) throw new IllegalArgumentException("月份与时区不能为空");

        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        YearMonth previousMonth = month.minusMonths(1);
        LocalDate prevStart = previousMonth.atDay(1);
        LocalDate prevEnd = previousMonth.atEndOfMonth();

        long totalAssetsInCents = toCents(financeService.getOverview(ownerId).totalAssets());
        long expenseInCents = toCents(expenseAnalyticsService.analyze(ownerId, monthStart, monthEnd).totalExpense());
        long prevExpenseInCents = toCents(expenseAnalyticsService.analyze(ownerId, prevStart, prevEnd).totalExpense());

        long recordMinutes = studyService.getStatistics(ownerId, monthStart, monthEnd, timezone).totalDurationSeconds() / 60;
        long prevRecordMinutes = studyService.getStatistics(ownerId, prevStart, prevEnd, timezone).totalDurationSeconds() / 60;

        RecordStats recordStats = recordService.summarizeActiveStats(ownerId);
        int documentCount = recordStats.count();
        long documentWordCount = recordStats.wordCount();

        // 待办完成率仅统计截至今日已生效的待办，避免未来月份提前计入未到期项。
        LocalDate today = LocalDate.now(timezone);
        int todoCount = 0;
        int completedTodos = 0;
        if (!monthStart.isAfter(today)) {
            LocalDate effectiveEnd = monthEnd.isAfter(today) ? today : monthEnd;
            for (DailyDaySummary day : dailyEventService.getDays(ownerId, monthStart, effectiveEnd)) {
                todoCount += day.todoCount();
                completedTodos += day.completedTodoCount();
            }
        }

        return new ProfileOverview(
                month,
                totalAssetsInCents,
                expenseInCents,
                recordMinutes,
                documentCount,
                documentWordCount,
                completedTodos,
                todoCount,
                null,
                growthRate(expenseInCents, prevExpenseInCents),
                growthRate(recordMinutes, prevRecordMinutes));
    }

    private long toCents(BigDecimal amount) {
        if (amount == null) return 0L;
        return amount.multiply(CENTS_SCALE).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private Double growthRate(long current, long previous) {
        if (previous <= 0L) return null;
        return (double) (current - previous) / (double) previous;
    }
}
