package butvan.agent.network.daily.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 日记录模块的领域值对象集合。 */
public final class DailyEventModels {

    private DailyEventModels() {
    }

    /** 所有日记录类型共享的小型写入接口。 */
    public interface DailyEventCommand {
        LocalDate eventDate();

        String title();

        String eventType();
    }

    /** 创建待办的领域命令。 */
    public record TodoCommand(
            LocalDate eventDate,
            String title,
            String time,
            String priority,
            String recurrence,
            Integer recurrenceWeekday,
            Integer recurrenceMonthDay)
            implements DailyEventCommand {
        public TodoCommand(LocalDate eventDate, String title, String time, String priority) {
            this(eventDate, title, time, priority, "none", null, null);
        }

        /** 兼容未指定重复日期的领域调用，由处理器沿用原有周一或自然月末规则。 */
        public TodoCommand(LocalDate eventDate, String title, String time, String priority, String recurrence) {
            this(eventDate, title, time, priority, recurrence, null, null);
        }

        @Override
        public String eventType() {
            return "todo";
        }
    }

    /** 待办类型的结构化详情。 */
    public record TodoDetails(
            String time,
            String priority,
            boolean completed,
            String recurrence,
            Integer recurrenceWeekday,
            Integer recurrenceMonthDay,
            Instant completedAt) {
        public TodoDetails(String time, String priority, boolean completed, String recurrence,
                           Integer recurrenceWeekday, Integer recurrenceMonthDay) {
            this(time, priority, completed, recurrence, recurrenceWeekday, recurrenceMonthDay, null);
        }
    }

    /** 创建日程的领域命令。 */
    public record ScheduleCommand(
            LocalDate eventDate,
            String title,
            String startTime,
            String endTime,
            String location,
            ZoneId timezone) implements DailyEventCommand {
        @Override
        public String eventType() {
            return "schedule";
        }
    }

    /** 日程类型的结构化详情。 */
    public record ScheduleDetails(
            String startTime,
            String endTime,
            String location,
            String timezone) {
    }

    /** 创建花销的领域命令。 */
    public record ExpenseCommand(
            LocalDate eventDate,
            String category,
            String note,
            BigDecimal amount,
            String time,
            String currency) implements DailyEventCommand {
        @Override
        public String title() {
            return note;
        }

        @Override
        public String eventType() {
            return "expense";
        }
    }

    /** 花销类型的结构化详情。 */
    public record ExpenseDetails(
            String category,
            String note,
            BigDecimal amount,
            String time,
            String currency) {
    }

    /** 财务模块收入在日历中的只读详情。 */
    public record IncomeDetails(
            String category,
            String note,
            BigDecimal amount,
            String time,
            String currency) {
    }

    /** 创建手记的领域命令。 */
    public record JournalCommand(LocalDate eventDate, String title, String body, String mood)
            implements DailyEventCommand {
        @Override
        public String eventType() {
            return "journal";
        }
    }

    /** 手记类型的结构化详情。 */
    public record JournalDetails(String body, String mood) {
    }

    /** 一条可出现在某日时间线中的日记录。 */
    public record DailyEvent(
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

    /** 统计用待办发生项，仅包含周期去重与完成计数所需字段，无标题、正文和其他业务数据。 */
    public record TodoOccurrence(String id, String recurrence, boolean completed) {}

    /** 某一天的全部日记录。 */
    public record DailyDay(LocalDate date, List<DailyEvent> events) {
    }

    /** 月历事项仅携带标题和类型，不包含正文及完整详情。 */
    public record CalendarItem(LocalDate date, String id, String type, String title) {}

    /** 周期便签使用的待办定义及当前自然周期完成状态。 */
    public record RecurringTodo(
            String id,
            String title,
            int version,
            String recurrence,
            LocalDate occurrenceDate,
            boolean completed) {
    }

    /** 月历等范围视图需要的轻量日汇总。 */
    public record DailyDaySummary(
            LocalDate date,
            int eventCount,
            int todoCount,
            int completedTodoCount,
            int scheduleCount,
            BigDecimal expenseTotal,
            String headline,
            List<CalendarItem> items) {
        /** 兼容基础统计组装，事项在 Service 统一补齐。 */
        public DailyDaySummary(LocalDate date, int eventCount, int todoCount, int completedTodoCount,
                int scheduleCount, BigDecimal expenseTotal, String headline) {
            this(date, eventCount, todoCount, completedTodoCount, scheduleCount, expenseTotal, headline, List.of());
        }
    }
}
