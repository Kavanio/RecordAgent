package butvan.agent.network.agenttool.calendar;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.tool.AgentToolModule;
import butvan.agent.agents.tool.result.ToolResult;
import butvan.agent.network.agenttool.common.BusinessToolErrors;
import butvan.agent.network.agenttool.common.BusinessToolExecutor;
import butvan.agent.network.daily.model.DailyEventModels.DailyDay;
import butvan.agent.network.daily.model.DailyEventModels.DailyEvent;
import butvan.agent.network.daily.model.DailyEventModels.ScheduleCommand;
import butvan.agent.network.daily.model.DailyEventModels.ScheduleDetails;
import butvan.agent.network.daily.model.DailyEventModels.TodoCommand;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.daily.service.DailyEventService;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 为 Agent 提供日程与待办的业务级操作。 */
@Component
@RequiredArgsConstructor
public class CalendarTool implements AgentToolModule {
    private static final String QUERY = "calendar_query";
    private static final String CREATE = "calendar_create";
    private static final String UPDATE = "calendar_update";
    private static final String COMPLETE = "calendar_set_todo_completed";
    private static final String DELETE = "calendar_delete";
    private static final int MAX_QUERY_DAYS = 62;
    private static final int MAX_RESULTS = 100;

    private final DailyEventService dailyEventService;
    private final CurrentUserProvider currentUserProvider;
    private final BusinessToolExecutor businessToolExecutor;

    @Tool(name = QUERY, description = "查询指定日期范围内的待办和日程。周期待办会按实际发生日期展开。", readOnly = true)
    public ToolResult<?> query(
            @ToolParam(name = "request", description = "查询范围、类型和可选筛选条件") QueryRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException("查询参数不能为空");
            LocalDate from = parseDate(request.from(), "from");
            LocalDate to = parseDate(request.to(), "to");
            if (from.isAfter(to) || from.plusDays(MAX_QUERY_DAYS - 1L).isBefore(to)) {
                throw new IllegalArgumentException("日历查询范围必须在 1 至 62 天内");
            }
            Set<String> kinds = normalizeKinds(request.kinds());
            int limit = normalizeLimit(request.limit());
            String keyword = clean(request.keyword()).toLowerCase(Locale.ROOT);
            String ownerId = currentUserProvider.currentUserId();
            Map<String, DailyEvent> definitions = new LinkedHashMap<>();
            List<CalendarItem> items = new ArrayList<>();
            boolean truncated = false;

            outer:
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                DailyDay day = dailyEventService.getDay(ownerId, date);
                for (DailyEvent event : day.events()) {
                    if (!kinds.contains(event.eventType())) continue;
                    if (!keyword.isEmpty() && !event.title().toLowerCase(Locale.ROOT).contains(keyword)) continue;
                    if (request.completed() != null) {
                        if (!(event.details() instanceof TodoDetails todo)
                                || todo.completed() != request.completed()) continue;
                    }
                    if (items.size() == limit) {
                        truncated = true;
                        break outer;
                    }
                    DailyEvent definition = "todo".equals(event.eventType())
                            ? definitions.computeIfAbsent(event.id(), ignored ->
                            dailyEventService.getEventDefinition(ownerId, event.id()))
                            : event;
                    items.add(toItem(event, definition.eventDate(), date));
                }
            }
            return ToolResult.success("找到 " + items.size() + " 条日历事项",
                    new QueryResult(from, to, items, truncated));
        } catch (RuntimeException exception) {
            return BusinessToolErrors.from(exception);
        }
    }

    @Tool(name = CREATE, description = "创建待办或日程。weekly 必须指定 weekday，monthly 固定在每月最后一天。")
    public ToolResult<?> create(
            @ToolParam(name = "request", description = "要创建的待办或日程；写入前会请求用户确认") CreateRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException("创建参数不能为空");
            String ownerId = currentUserProvider.currentUserId();
            return businessToolExecutor.write(ownerId, CREATE, request.idempotencyKey(), () -> {
                LocalDate startsOn = parseDate(request.startsOn(), "startsOn");
                DailyEvent created = switch (required(request.kind(), "kind")) {
                    case "todo" -> dailyEventService.create(ownerId, todoCommand(request, startsOn));
                    case "schedule" -> dailyEventService.create(ownerId, scheduleCommand(request, startsOn));
                    default -> throw new IllegalArgumentException("kind 只能是 todo 或 schedule");
                };
                LocalDate occurrenceDate = firstOccurrence(created);
                return ToolResult.success(createSummary(created), toItem(created, created.eventDate(), occurrenceDate));
            });
        } catch (RuntimeException exception) {
            return BusinessToolErrors.from(exception);
        }
    }

    @Tool(name = UPDATE, description = "部分更新一条待办或日程；未传字段保持不变，不能改变事项类型。")
    public ToolResult<?> update(
            @ToolParam(name = "request", description = "事项 ID、版本和需要修改的字段；写入前会请求用户确认") UpdateRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException("更新参数不能为空");
            String ownerId = currentUserProvider.currentUserId();
            return businessToolExecutor.write(ownerId, UPDATE, request.idempotencyKey(), () -> {
                DailyEvent existing = dailyEventService.getEventDefinition(ownerId, required(request.eventId(), "eventId"));
                DailyEvent updated;
                if (existing.details() instanceof TodoDetails todo) {
                    RecurrenceInput recurrence = request.recurrence() == null
                            ? new RecurrenceInput(todo.recurrence(), todo.recurrenceWeekday(), todo.recurrenceMonthDay())
                            : request.recurrence();
                    validateRecurrence(recurrence);
                    updated = dailyEventService.update(ownerId, existing.id(), request.expectedVersion(), new TodoCommand(
                            optionalDate(request.startsOn(), existing.eventDate()),
                            preserve(request.title(), existing.title()),
                            preserveNullable(request.time(), todo.time()),
                            preserve(request.priority(), todo.priority()),
                            recurrence.type(), recurrence.weekday(), recurrence.dayOfMonth()));
                } else if (existing.details() instanceof ScheduleDetails schedule) {
                    if (request.recurrence() != null) throw new IllegalArgumentException("日程不支持重复待办规则");
                    updated = dailyEventService.update(ownerId, existing.id(), request.expectedVersion(), new ScheduleCommand(
                            optionalDate(request.startsOn(), existing.eventDate()),
                            preserve(request.title(), existing.title()),
                            preserveNullable(request.startTime(), schedule.startTime()),
                            preserveNullable(request.endTime(), schedule.endTime()),
                            preserveNullable(request.location(), schedule.location()),
                            parseTimezone(preserve(request.timezone(), schedule.timezone()))));
                } else {
                    throw new IllegalArgumentException("只能修改待办或日程");
                }
                return ToolResult.success("已更新日历事项：“" + updated.title() + "”",
                        toItem(updated, updated.eventDate(), firstOccurrence(updated)));
            });
        } catch (RuntimeException exception) {
            return BusinessToolErrors.from(exception);
        }
    }

    @Tool(name = COMPLETE, description = "设置某次待办是否完成。occurrenceDate 必须是该待办实际发生的日期。")
    public ToolResult<?> setTodoCompleted(
            @ToolParam(name = "request", description = "待办 ID、发生日期、完成状态和版本；写入前会请求用户确认") CompletionRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException("完成状态参数不能为空");
            String ownerId = currentUserProvider.currentUserId();
            return businessToolExecutor.write(ownerId, COMPLETE, request.idempotencyKey(), () -> {
                LocalDate occurrenceDate = parseDate(request.occurrenceDate(), "occurrenceDate");
                DailyEvent updated = dailyEventService.setTodoCompleted(
                        ownerId, required(request.eventId(), "eventId"), request.completed(),
                        request.expectedVersion(), occurrenceDate);
                return ToolResult.success(
                        request.completed() ? "已完成待办：“" + updated.title() + "”" : "已取消完成待办：“" + updated.title() + "”",
                        toItem(updated, dailyEventService.getEventDefinition(ownerId, updated.id()).eventDate(), occurrenceDate));
            });
        } catch (RuntimeException exception) {
            return BusinessToolErrors.from(exception);
        }
    }

    @Tool(name = DELETE, description = "删除整个日历事项。对于周期待办会删除整个重复系列，而非单次发生。")
    public ToolResult<?> delete(
            @ToolParam(name = "request", description = "事项 ID 和版本；写入前会请求用户确认") DeleteRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException("删除参数不能为空");
            String ownerId = currentUserProvider.currentUserId();
            return businessToolExecutor.write(ownerId, DELETE, request.idempotencyKey(), () -> {
                DailyEvent existing = dailyEventService.getEventDefinition(ownerId, required(request.eventId(), "eventId"));
                if (!(existing.details() instanceof TodoDetails) && !(existing.details() instanceof ScheduleDetails)) {
                    throw new IllegalArgumentException("只能删除待办或日程");
                }
                dailyEventService.delete(ownerId, existing.id(), request.expectedVersion());
                String scope = existing.details() instanceof TodoDetails todo && !"none".equals(todo.recurrence())
                        ? "series" : "single";
                return ToolResult.success("已删除日历事项：“" + existing.title() + "”",
                        Map.of("eventId", existing.id(), "deletedScope", scope));
            });
        } catch (RuntimeException exception) {
            return BusinessToolErrors.from(exception);
        }
    }

    private TodoCommand todoCommand(CreateRequest request, LocalDate startsOn) {
        RecurrenceInput recurrence = request.recurrence() == null
                ? new RecurrenceInput("none", null, null) : request.recurrence();
        validateRecurrence(recurrence);
        return new TodoCommand(startsOn, request.title(), cleanToNull(request.time()),
                defaulted(request.priority(), "medium"), recurrence.type(), recurrence.weekday(), recurrence.dayOfMonth());
    }

    private ScheduleCommand scheduleCommand(CreateRequest request, LocalDate startsOn) {
        if (request.recurrence() != null) throw new IllegalArgumentException("日程不支持重复待办规则");
        return new ScheduleCommand(startsOn, request.title(), cleanToNull(request.startTime()),
                cleanToNull(request.endTime()), cleanToNull(request.location()), parseTimezone(request.timezone()));
    }

    private void validateRecurrence(RecurrenceInput recurrence) {
        String type = required(recurrence.type(), "recurrence.type");
        switch (type) {
            case "none", "daily" -> {
                if (recurrence.weekday() != null || recurrence.dayOfMonth() != null) {
                    throw new IllegalArgumentException(type + " 重复规则不能指定 weekday 或 dayOfMonth");
                }
            }
            case "weekly" -> {
                if (recurrence.weekday() != null && (recurrence.weekday() < 1 || recurrence.weekday() > 7)) {
                    throw new IllegalArgumentException("weekly 重复规则如果指定 weekday，必须在 1 至 7 之间");
                }
                if (recurrence.dayOfMonth() != null) throw new IllegalArgumentException("weekly 不能指定 dayOfMonth");
            }
            case "monthly" -> {
                if (recurrence.dayOfMonth() != null && (recurrence.dayOfMonth() < 1 || recurrence.dayOfMonth() > 31)) {
                    throw new IllegalArgumentException("monthly 重复规则如果指定 dayOfMonth，必须在 1 至 31 之间");
                }
                if (recurrence.weekday() != null) throw new IllegalArgumentException("monthly 不能指定 weekday");
            }
            default -> throw new IllegalArgumentException("recurrence.type 只能是 none、daily、weekly 或 monthly");
        }
    }

    private CalendarItem toItem(DailyEvent event, LocalDate startsOn, LocalDate occurrenceDate) {
        if (event.details() instanceof TodoDetails todo) {
            return new CalendarItem(event.id(), "todo", event.title(), startsOn, occurrenceDate,
                    event.version(), event.source(),
                    new TodoView(todo.time(), todo.priority(), todo.completed(), new RecurrenceInput(
                            todo.recurrence(), todo.recurrenceWeekday(), todo.recurrenceMonthDay())), null);
        }
        if (event.details() instanceof ScheduleDetails schedule) {
            return new CalendarItem(event.id(), "schedule", event.title(), startsOn, occurrenceDate,
                    event.version(), event.source(), null,
                    new ScheduleView(schedule.startTime(), schedule.endTime(), schedule.location(), schedule.timezone()));
        }
        throw new IllegalArgumentException("指定事项不是待办或日程");
    }

    private LocalDate firstOccurrence(DailyEvent event) {
        if (!(event.details() instanceof TodoDetails todo)) return event.eventDate();
        return switch (todo.recurrence()) {
            case "weekly" -> todo.recurrenceWeekday() != null
                    ? event.eventDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(todo.recurrenceWeekday())))
                    : event.eventDate();
            case "monthly" -> todo.recurrenceMonthDay() != null
                    ? firstMonthlyOccurrence(event.eventDate(), todo.recurrenceMonthDay())
                    : event.eventDate();
            default -> event.eventDate();
        };
    }

    private LocalDate firstMonthlyOccurrence(LocalDate startsOn, int monthDay) {
        int day = Math.min(monthDay, startsOn.lengthOfMonth());
        LocalDate candidate = startsOn.withDayOfMonth(day);
        return candidate.isBefore(startsOn)
                ? candidate.plusMonths(1).withDayOfMonth(Math.min(monthDay, candidate.plusMonths(1).lengthOfMonth()))
                : candidate;
    }

    private String createSummary(DailyEvent event) {
        if (event.details() instanceof TodoDetails todo) {
            String recurrence = switch (todo.recurrence()) {
                case "daily" -> "每天";
                case "weekly" -> todo.recurrenceWeekday() != null
                        ? "每周" + DayOfWeek.of(todo.recurrenceWeekday()).getDisplayName(
                                java.time.format.TextStyle.FULL, Locale.CHINA)
                        : "每周（便签）";
                case "monthly" -> todo.recurrenceMonthDay() != null
                        ? (todo.recurrenceMonthDay() == 31 ? "每月最后一天" : "每月" + todo.recurrenceMonthDay() + "日")
                        : "每月（便签）";
                default -> "不重复";
            };
            return "已创建待办：“" + event.title() + "”（" + recurrence + "）";
        }
        return "已创建日程：“" + event.title() + "”";
    }

    private Set<String> normalizeKinds(List<String> rawKinds) {
        Set<String> kinds = rawKinds == null || rawKinds.isEmpty()
                ? new LinkedHashSet<>(List.of("todo", "schedule"))
                : new LinkedHashSet<>(rawKinds);
        if (!Set.of("todo", "schedule").containsAll(kinds)) {
            throw new IllegalArgumentException("kinds 只能包含 todo 或 schedule");
        }
        return kinds;
    }

    private int normalizeLimit(Integer value) {
        if (value == null) return 50;
        if (value < 1 || value > MAX_RESULTS) throw new IllegalArgumentException("limit 必须在 1 至 100 之间");
        return value;
    }

    private LocalDate optionalDate(String value, LocalDate fallback) {
        return value == null ? fallback : parseDate(value, "startsOn");
    }

    private LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(required(value, field));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(field + " 必须使用 YYYY-MM-DD 格式");
        }
    }

    private ZoneId parseTimezone(String value) {
        try {
            return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value.trim());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("timezone 必须是合法 IANA 时区");
        }
    }

    private String preserve(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private String preserveNullable(String value, String fallback) {
        return value == null ? fallback : cleanToNull(value);
    }

    private String defaulted(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private String required(String value, String field) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) throw new IllegalArgumentException(field + " 不能为空");
        return cleaned;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String cleanToNull(String value) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? null : cleaned;
    }

    public record QueryRequest(
            String from, String to, List<String> kinds, Boolean completed, String keyword, Integer limit) {
    }

    public record CreateRequest(
            String kind, String startsOn, String title, String time, String priority,
            RecurrenceInput recurrence, String startTime, String endTime, String location,
            String timezone, String idempotencyKey) {
    }

    public record UpdateRequest(
            String eventId, int expectedVersion, String startsOn, String title, String time, String priority,
            RecurrenceInput recurrence, String startTime, String endTime, String location,
            String timezone, String idempotencyKey) {
    }

    public record CompletionRequest(
            String eventId, String occurrenceDate, boolean completed, int expectedVersion, String idempotencyKey) {
    }

    public record DeleteRequest(String eventId, int expectedVersion, String idempotencyKey) {
    }

    public record RecurrenceInput(String type, Integer weekday, Integer dayOfMonth) {
    }

    public record QueryResult(LocalDate from, LocalDate to, List<CalendarItem> items, boolean truncated) {
    }

    public record CalendarItem(
            String id, String kind, String title, LocalDate startsOn, LocalDate occurrenceDate,
            int version, String source, TodoView todo, ScheduleView schedule) {
    }

    public record TodoView(String time, String priority, boolean completed, RecurrenceInput recurrence) {
    }

    public record ScheduleView(String startTime, String endTime, String location, String timezone) {
    }
}
