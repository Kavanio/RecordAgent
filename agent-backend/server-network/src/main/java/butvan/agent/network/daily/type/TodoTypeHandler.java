package butvan.agent.network.daily.type;

import butvan.agent.network.daily.model.DailyEventModels.TodoCommand;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 待办详情处理器。 */
@Component
@RequiredArgsConstructor
public class TodoTypeHandler implements DailyEventTypeHandler<TodoCommand> {
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    @Override public String eventType() { return "todo"; }
    @Override public Class<TodoCommand> commandType() { return TodoCommand.class; }

    @Override
    public void validate(TodoCommand command) {
        if (command.title() == null || command.title().isBlank()) throw new IllegalArgumentException("待办内容不能为空");
        if (!Set.of("high", "medium", "low").contains(command.priority())) throw new IllegalArgumentException("待办优先级不合法");
        if (!Set.of("none", "daily", "weekly", "monthly").contains(normalizeRecurrence(command.recurrence()))) {
            throw new IllegalArgumentException("待办周期不合法");
        }
        normalizeRecurrenceWeekday(command.recurrence(), command.recurrenceWeekday());
        normalizeRecurrenceMonthDay(command.recurrence(), command.recurrenceMonthDay());
        parseOptionalTime(command.time(), "待办时间不合法");
    }

    @Override
    public void insert(String eventId, TodoCommand command) {
        jdbcTemplate.update("""
                INSERT INTO todo_detail (
                    event_id, todo_time, priority, completed, recurrence, recurrence_weekday, recurrence_month_day
                ) VALUES (?, ?, ?, 0, ?, ?, ?)
                """, eventId, blankToNull(command.time()), command.priority(), normalizeRecurrence(command.recurrence()),
                normalizeRecurrenceWeekday(command.recurrence(), command.recurrenceWeekday()),
                normalizeRecurrenceMonthDay(command.recurrence(), command.recurrenceMonthDay()));
    }

    @Override
    public void update(String eventId, TodoCommand command) {
        TodoRecurrenceRule previousRule = findRecurrenceRule(eventId);
        TodoRecurrenceRule nextRule = new TodoRecurrenceRule(
                normalizeRecurrence(command.recurrence()),
                normalizeRecurrenceWeekday(command.recurrence(), command.recurrenceWeekday()),
                normalizeRecurrenceMonthDay(command.recurrence(), command.recurrenceMonthDay()));
        int updated = jdbcTemplate.update(
                """
                UPDATE todo_detail
                SET todo_time = ?, priority = ?, recurrence = ?, recurrence_weekday = ?, recurrence_month_day = ?
                WHERE event_id = ?
                """, blankToNull(command.time()), command.priority(), normalizeRecurrence(command.recurrence()),
                normalizeRecurrenceWeekday(command.recurrence(), command.recurrenceWeekday()),
                normalizeRecurrenceMonthDay(command.recurrence(), command.recurrenceMonthDay()), eventId);
        if (updated != 1) throw new IllegalArgumentException("待办不存在");
        if (!previousRule.equals(nextRule)) {
            jdbcTemplate.update("DELETE FROM todo_completion WHERE event_id = ?", eventId);
        }
    }

    /** 修改待办完成状态。 */
    public void setCompleted(String eventId, LocalDate occurrenceDate, String recurrence, boolean completed) {
        String periodStart = periodStart(occurrenceDate, recurrence).toString();
        if (completed) {
            String completedAt = Instant.now().toString();
            String occurrenceMarker = occurrenceDate + "T00:00:00";
            jdbcTemplate.update("""
                    INSERT INTO todo_completion (event_id, period_start, completed_at, completed_time)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(event_id, period_start) DO NOTHING
                    """, eventId, periodStart, occurrenceMarker, completedAt);
        } else {
            jdbcTemplate.update("DELETE FROM todo_completion WHERE event_id = ? AND period_start = ?",
                    eventId, periodStart);
        }
    }

    /** 读取待办周期，供完成状态按日定位所属周期。 */
    public TodoRecurrenceRule findRecurrenceRule(String eventId) {
        List<TodoRecurrenceRule> values = jdbcTemplate.query(
                "SELECT recurrence, recurrence_weekday, recurrence_month_day FROM todo_detail WHERE event_id = ?",
                (resultSet, rowNumber) -> new TodoRecurrenceRule(
                        normalizeRecurrence(resultSet.getString("recurrence")),
                        nullableInteger(resultSet, "recurrence_weekday"),
                        nullableInteger(resultSet, "recurrence_month_day")), eventId);
        if (values.isEmpty()) throw new IllegalArgumentException("待办不存在");
        return values.getFirst();
    }

    /** 判断目标日期是否属于待办的有效重复规则。 */
    public boolean occursOn(LocalDate startDate, LocalDate occurrenceDate, TodoRecurrenceRule rule) {
        LocalDate effectiveStart = switch (rule.recurrence()) {
            case "weekly" -> periodStart(startDate, "weekly");
            case "monthly" -> periodStart(startDate, "monthly");
            default -> startDate;
        };
        if (occurrenceDate.isBefore(effectiveStart)) return false;
        return switch (rule.recurrence()) {
            case "none" -> occurrenceDate.equals(startDate);
            case "daily" -> true;
            case "weekly" -> rule.recurrenceWeekday() == null
                    || occurrenceDate.getDayOfWeek().getValue() == rule.recurrenceWeekday();
            case "monthly" -> rule.recurrenceMonthDay() == null
                    || occurrenceDate.getDayOfMonth() == Math.min(rule.recurrenceMonthDay(), occurrenceDate.lengthOfMonth());
            default -> false;
        };
    }

    /** 按查询日期加载待办详情，周期待办的完成状态由所属日、周或月独立计算。 */
    public Map<String, Object> loadDetailsForOccurrence(List<String> eventIds, LocalDate occurrenceDate) {
        if (eventIds.isEmpty()) return Map.of();
        List<TodoRow> todos = namedJdbcTemplate.query("""
                SELECT event_id, todo_time, priority, recurrence, recurrence_weekday, recurrence_month_day
                FROM todo_detail
                WHERE event_id IN (:ids)
                """, Map.of("ids", eventIds), (resultSet, rowNumber) -> new TodoRow(
                resultSet.getString("event_id"), resultSet.getString("todo_time"),
                resultSet.getString("priority"), normalizeRecurrence(resultSet.getString("recurrence")),
                nullableInteger(resultSet, "recurrence_weekday"),
                nullableInteger(resultSet, "recurrence_month_day")));
        Map<String, String> completions = namedJdbcTemplate.query("""
                SELECT event_id, period_start, completed_at, completed_time
                FROM todo_completion
                WHERE event_id IN (:ids)
                """, Map.of("ids", eventIds), resultSet -> {
                    Map<String, String> result = new LinkedHashMap<>();
                    while (resultSet.next()) {
                        String completedAt = resultSet.getString("completed_time");
                        if (completedAt == null || completedAt.isBlank()) {
                            // 兼容升级前的完成记录；旧字段只保留了本地时分与发生日期。
                            completedAt = resultSet.getString("completed_at");
                        }
                        result.put(resultSet.getString("event_id") + ":" + resultSet.getString("period_start"), completedAt);
                    }
                    return result;
                });
        Map<String, Object> result = new LinkedHashMap<>();
        for (TodoRow todo : todos) {
            String key = periodStart(occurrenceDate, todo.recurrence()).toString();
            String completedAt = completions.get(todo.eventId() + ":" + key);
            boolean completed = completedAt != null;
            result.put(todo.eventId(), new TodoDetails(
                    todo.time(), todo.priority(), completed, todo.recurrence(),
                    todo.recurrenceWeekday(), todo.recurrenceMonthDay(), parseCompletedAt(completedAt)));
        }
        return result;
    }

    /** 兼容历史本地时间字符串；新记录使用 UTC Instant。 */
    private Instant parseCompletedAt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalStateException("待办完成时间格式无效", e);
            }
        }
    }

    @Override
    public Map<String, Object> loadDetails(List<String> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        return namedJdbcTemplate.query("""
                SELECT event_id, todo_time, priority, recurrence, recurrence_weekday, recurrence_month_day
                FROM todo_detail WHERE event_id IN (:ids)
                """,
                Map.of("ids", eventIds), resultSet -> {
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    while (resultSet.next()) result.put(resultSet.getString("event_id"), new TodoDetails(
                            resultSet.getString("todo_time"), resultSet.getString("priority"), false,
                            normalizeRecurrence(resultSet.getString("recurrence")),
                            nullableInteger(resultSet, "recurrence_weekday"),
                            nullableInteger(resultSet, "recurrence_month_day")));
                    return result;
                });
    }

    private LocalDate periodStart(LocalDate date, String recurrence) {
        return switch (normalizeRecurrence(recurrence)) {
            case "weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "monthly" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private String normalizeRecurrence(String recurrence) {
        return recurrence == null || recurrence.isBlank() ? "none" : recurrence.trim();
    }

    private Integer normalizeRecurrenceWeekday(String recurrence, Integer weekday) {
        if (!"weekly".equals(normalizeRecurrence(recurrence))) return null;
        if (weekday == null) return null;
        if (weekday < 1 || weekday > 7) throw new IllegalArgumentException("每周重复日期必须在周一到周日之间");
        return weekday;
    }

    private Integer normalizeRecurrenceMonthDay(String recurrence, Integer monthDay) {
        if (!"monthly".equals(normalizeRecurrence(recurrence))) return null;
        if (monthDay == null) return null;
        if (monthDay < 1 || monthDay > 31) throw new IllegalArgumentException("每月重复日期必须在 1 到 31 之间");
        return monthDay;
    }

    private Integer nullableInteger(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    /** 待办重复方式及其具体发生日期。 */
    public record TodoRecurrenceRule(String recurrence, Integer recurrenceWeekday, Integer recurrenceMonthDay) {
    }

    private record TodoRow(
            String eventId,
            String time,
            String priority,
            String recurrence,
            Integer recurrenceWeekday,
            Integer recurrenceMonthDay) {
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private void parseOptionalTime(String value, String message) {
        if (value == null || value.isBlank()) return;
        try {
            java.time.LocalTime.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException(message);
        }
    }
}
