package butvan.agent.network.daily.dto;

import butvan.agent.network.daily.model.DailyEventModels.DailyDay;
import butvan.agent.network.daily.model.DailyEventModels.DailyDaySummary;
import butvan.agent.network.daily.model.DailyEventModels.DailyEvent;
import butvan.agent.network.daily.model.DailyEventModels.ExpenseDetails;
import butvan.agent.network.daily.model.DailyEventModels.JournalDetails;
import butvan.agent.network.daily.model.DailyEventModels.IncomeDetails;
import butvan.agent.network.daily.model.DailyEventModels.ScheduleDetails;
import butvan.agent.network.daily.model.DailyEventModels.RecurringTodo;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.study.model.StudyModels.StudyDetails;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 日记录读取接口的独立响应 DTO 与领域映射。 */
public final class DailyEventResponses {

    private DailyEventResponses() {
    }

    /** 一条日记录响应。 */
    public record EventResponse(
            String id,
            LocalDate eventDate,
            String eventType,
            String title,
            String source,
            String status,
            int version,
            Instant createdAt,
            Instant updatedAt,
            Object details) {
    }

    /** 某日完整时间线响应。 */
    public record DayResponse(LocalDate date, List<EventResponse> events) {
    }

    /** 日期范围内单日摘要响应。 */
    public record DaySummaryResponse(
            LocalDate date,
            int eventCount,
            int todoCount,
            int completedTodoCount,
            int scheduleCount,
            BigDecimal expenseTotal,
            String headline,
            List<CalendarItemResponse> items) {
    }

    /** 月历轻量事项，不暴露内部记录及正文。 */
    public record CalendarItemResponse(String id, String type, String title) {}

    /** 周期便签中的待办响应。 */
    public record RecurringTodoResponse(
            String id,
            String title,
            int version,
            String recurrence,
            LocalDate occurrenceDate,
            boolean completed) {
    }

    /** 待办详情响应。 */
    public record TodoDetailResponse(
            String time,
            String priority,
            boolean completed,
            String recurrence,
            Integer recurrenceWeekday,
            Integer recurrenceMonthDay,
            Instant completedAt) {
    }

    /** 日程详情响应。 */
    public record ScheduleDetailResponse(String startTime, String endTime, String location, String timezone) {
    }

    /** 花销详情响应。 */
    public record ExpenseDetailResponse(
            String category, String note, BigDecimal amount, String time, String currency) {
    }

    /** 财务收入在日历中的只读详情响应。 */
    public record IncomeDetailResponse(
            String category, String note, BigDecimal amount, String time, String currency) {
    }

    /** 手记详情响应。 */
    public record JournalDetailResponse(String body, String mood) {
    }

    /** 学习时段详情响应。 */
    public record StudyDetailResponse(String startedAt, String endedAt, String category, String timezone) {
    }

    /** 将领域日记录转换为协议 DTO。 */
    public static EventResponse from(DailyEvent event) {
        return new EventResponse(
                event.id(), event.eventDate(), event.eventType(), event.title(), event.source(), event.status(),
                event.version(), event.createdAt(), event.updatedAt(), mapDetails(event.details()));
    }

    /** 将某日领域结果转换为协议 DTO。 */
    public static DayResponse from(DailyDay day) {
        return new DayResponse(day.date(), day.events().stream().map(DailyEventResponses::from).toList());
    }

    /** 将范围摘要转换为协议 DTO。 */
    public static DaySummaryResponse from(DailyDaySummary summary) {
        return new DaySummaryResponse(
                summary.date(), summary.eventCount(), summary.todoCount(), summary.completedTodoCount(),
                summary.scheduleCount(), summary.expenseTotal(), summary.headline(),
                summary.items().stream().map(item -> new CalendarItemResponse(item.id(), item.type(), item.title())).toList());
    }

    /** 将周期待办转换为便签读取 DTO。 */
    public static RecurringTodoResponse from(RecurringTodo todo) {
        return new RecurringTodoResponse(
                todo.id(), todo.title(), todo.version(), todo.recurrence(), todo.occurrenceDate(), todo.completed());
    }

    private static Object mapDetails(Object details) {
        if (details instanceof TodoDetails todo) {
            return new TodoDetailResponse(
                    todo.time(), todo.priority(), todo.completed(), todo.recurrence(),
                    todo.recurrenceWeekday(), todo.recurrenceMonthDay(), todo.completedAt());
        }
        if (details instanceof ScheduleDetails schedule) {
            return new ScheduleDetailResponse(
                    schedule.startTime(), schedule.endTime(), schedule.location(), schedule.timezone());
        }
        if (details instanceof ExpenseDetails expense) {
            return new ExpenseDetailResponse(
                    expense.category(), expense.note(), expense.amount(), expense.time(), expense.currency());
        }
        if (details instanceof IncomeDetails income) {
            return new IncomeDetailResponse(
                    income.category(), income.note(), income.amount(), income.time(), income.currency());
        }
        if (details instanceof JournalDetails journal) {
            return new JournalDetailResponse(journal.body(), journal.mood());
        }
        if (details instanceof StudyDetails study) {
            return new StudyDetailResponse(study.startedAt(), study.endedAt(), study.category(), study.timezone());
        }
        return null;
    }
}
