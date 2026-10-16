package butvan.agent.network.daily.service;

import butvan.agent.network.daily.model.DailyEventModels.CalendarItem;
import butvan.agent.network.daily.model.DailyEventModels.DailyDay;
import butvan.agent.network.daily.model.DailyEventModels.DailyDaySummary;
import butvan.agent.network.daily.model.DailyEventModels.DailyEventCommand;
import butvan.agent.network.daily.model.DailyEventModels.DailyEvent;
import butvan.agent.network.daily.repository.DailyEventRepository;
import butvan.agent.network.daily.repository.DailyEventRepository.DailyEventRow;
import butvan.agent.network.daily.type.DailyEventTypeRegistry;
import butvan.agent.network.daily.type.TodoTypeHandler;
import butvan.agent.network.daily.model.DailyEventModels.JournalCommand;
import butvan.agent.network.daily.model.DailyEventModels.JournalDetails;
import butvan.agent.network.daily.model.DailyEventModels.ExpenseDetails;
import butvan.agent.network.daily.model.DailyEventModels.IncomeDetails;
import butvan.agent.network.daily.model.DailyEventModels.RecurringTodo;
import butvan.agent.network.record.service.RecordJournalProjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** 日记录公开领域接口，隐藏 SQLite 与类型详情的持久化过程。 */
@Service
@RequiredArgsConstructor
public class DailyEventService {

    private final DailyEventRepository repository;
    private final DailyEventTypeRegistry typeRegistry;
    private final TodoTypeHandler todoTypeHandler;
    private final ExpenseAnalyticsService expenseAnalyticsService;
    private final RecordJournalProjectionService recordJournalProjectionService;

    /** 创建任意已注册类型的日记录。 */
    @Transactional
    public DailyEvent create(String ownerId, DailyEventCommand command) {
        validate(ownerId, command);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String title = command.title() == null || command.title().isBlank() ? "无标题记录" : command.title().trim();
        repository.insertEvent(id, ownerId, command.eventDate(), command.eventType(), title, now);
        typeRegistry.insert(id, command);
        if (command instanceof JournalCommand journal) {
            recordJournalProjectionService.upsertFromCalendar(ownerId, id, journal.eventDate(), title, journal.body());
        }
        return readStoredEvent(ownerId, id, command.eventDate());
    }

    /** 将记录资料库中的每日手记幂等同步到日历，稳定来源引用可避免重复创建。 */
    @Transactional
    public void syncRecordJournal(String ownerId, String recordId, LocalDate date, String title, String body) {
        if (recordId == null || recordId.isBlank()) throw new IllegalArgumentException("记录 ID 不能为空");
        JournalCommand command = new JournalCommand(date, title, body == null ? "" : body, "来自记录");
        validate(ownerId, command);
        DailyEventRow existing = repository.findBySourceReference(ownerId, "record", recordId).orElse(null);
        if (existing == null) {
            String eventId = "record-" + recordId;
            Instant now = Instant.now();
            String cleanTitle = title == null || title.isBlank() ? "无标题记录" : title.trim();
            repository.insertEvent(eventId, ownerId, date, command.eventType(), cleanTitle,
                    "record", recordId, now);
            typeRegistry.insert(eventId, command);
            return;
        }
        typeRegistry.update(existing.id(), command);
        String cleanTitle = title == null || title.isBlank() ? "无标题记录" : title.trim();
        if (!repository.updateEvent(ownerId, existing.id(), existing.version(), date, cleanTitle, Instant.now())) {
            throw new IllegalStateException("手记同步时已被其他操作修改，请重试");
        }
    }

    /** 移除由记录资料库同步而来的日历手记。 */
    @Transactional
    public void removeRecordJournal(String ownerId, String recordId) {
        repository.findBySourceReference(ownerId, "record", recordId).ifPresent(existing -> {
            if (!repository.delete(ownerId, existing.id(), existing.version())) {
                throw new IllegalStateException("手记同步时已被其他操作修改，请重试");
            }
        });
    }

    /** 由记录页面更新原本创建于日历的手记，不产生新的同步投影。 */
    @Transactional
    public void syncCalendarJournalFromRecord(
            String ownerId, String eventId, LocalDate date, String title, String body) {
        if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("日记录 ID 不能为空");
        DailyEventRow existing = repository.findById(ownerId, eventId).orElse(null);
        String mood = existing == null ? "未标注心情" : typeRegistry
                .loadDetails(Map.of("journal", List.of(eventId))).get(eventId) instanceof JournalDetails details
                ? details.mood() : "未标注心情";
        JournalCommand command = new JournalCommand(date, title, body == null ? "" : body, mood);
        validate(ownerId, command);
        String cleanTitle = title == null || title.isBlank() ? "无标题记录" : title.trim();
        if (existing == null) {
            Instant now = Instant.now();
            repository.insertEvent(eventId, ownerId, date, "journal", cleanTitle, now);
            typeRegistry.insert(eventId, command);
            return;
        }
        if (!"journal".equals(existing.eventType())) throw new IllegalArgumentException("指定日记录不是手记");
        typeRegistry.update(eventId, command);
        if (!repository.updateEvent(ownerId, eventId, existing.version(), date, cleanTitle, Instant.now())) {
            throw new IllegalStateException("手记同步时已被其他操作修改，请重试");
        }
    }

    /** 由记录页面移除原本创建于日历的手记，保留记录侧的回收站条目。 */
    @Transactional
    public void removeCalendarJournalFromRecord(String ownerId, String eventId) {
        if (eventId == null || eventId.isBlank()) return;
        repository.findById(ownerId, eventId).ifPresent(existing -> {
            if (!"journal".equals(existing.eventType())) throw new IllegalArgumentException("指定日记录不是手记");
            if (!repository.delete(ownerId, eventId, existing.version())) {
                throw new IllegalStateException("手记同步时已被其他操作修改，请重试");
            }
        });
    }

    /** 查询某一天的全部日记录。 */
    @Transactional(readOnly = true)
    public DailyDay getDay(String ownerId, LocalDate date) {
        if (ownerId == null || ownerId.isBlank() || date == null) {
            throw new IllegalArgumentException("用户与日期不能为空");
        }
        List<DailyEventRow> rows = repository.findDay(ownerId, date);
        Map<String, List<String>> idsByType = rows.stream().collect(Collectors.groupingBy(
                DailyEventRow::eventType,
                Collectors.mapping(DailyEventRow::id, Collectors.toList())));
        Map<String, Object> detailsById = typeRegistry.loadDetails(idsByType);
        List<String> todoIds = idsByType.getOrDefault("todo", List.of());
        detailsById.putAll(todoTypeHandler.loadDetailsForOccurrence(todoIds, date));
        List<DailyEvent> events = new ArrayList<>(rows.stream().map(row -> new DailyEvent(
                row.id(), "todo".equals(row.eventType()) ? date : row.eventDate(), row.eventType(), row.title(), row.source(), row.status(),
                row.version(), row.createdAt(), row.updatedAt(), detailsById.get(row.id()))).toList());
        expenseAnalyticsService.findFinanceExpenses(ownerId, date).forEach(expense -> events.add(new DailyEvent(
                "finance-" + expense.id(), expense.date(), "expense", expense.note(), "finance", "confirmed",
                0, expense.createdAt(), expense.createdAt(), new ExpenseDetails(
                expense.category(), expense.note(), expense.amount(), expense.time().toString(), expense.currency()))));
        expenseAnalyticsService.findFinanceIncomes(ownerId, date).forEach(income -> events.add(new DailyEvent(
                "finance-income-" + income.id(), income.date(), "income", income.note(), "finance", "confirmed",
                0, income.createdAt(), income.createdAt(), new IncomeDetails(
                income.category(), income.note(), income.amount(), income.time().toString(), income.currency()))));
        events.sort(java.util.Comparator.comparing(DailyEvent::createdAt).thenComparing(DailyEvent::id));
        return new DailyDay(date, events);
    }

    /** 查询一条日记录的持久化定义，周期待办返回生效日而不是某次发生日。 */
    @Transactional(readOnly = true)
    public DailyEvent getEventDefinition(String ownerId, String eventId) {
        DailyEventRow row = requireEvent(ownerId, eventId);
        Object details = typeRegistry.loadDetails(Map.of(row.eventType(), List.of(eventId))).get(eventId);
        return new DailyEvent(
                row.id(), row.eventDate(), row.eventType(), row.title(), row.source(), row.status(),
                row.version(), row.createdAt(), row.updatedAt(), details);
    }

    /** 查询日期范围摘要。 */
    @Transactional(readOnly = true)
    public List<DailyDaySummary> getDays(String ownerId, LocalDate from, LocalDate to) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("日期范围不合法");
        Map<LocalDate, DailyDaySummary> summaries = repository.findDaySummaries(ownerId, from, to).stream()
                .collect(Collectors.toMap(DailyDaySummary::date, item -> item, (left, right) -> left, LinkedHashMap::new));
        repository.findRecurringTodoSummaries(ownerId, from, to, LocalDate.now()).forEach(recurring ->
                summaries.compute(recurring.date(), (date, current) -> current == null
                        ? recurring
                        : new DailyDaySummary(date, current.eventCount() + recurring.eventCount(),
                        current.todoCount() + recurring.todoCount(),
                        current.completedTodoCount() + recurring.completedTodoCount(), current.scheduleCount(),
                        current.expenseTotal(), current.headline() == null ? recurring.headline() : current.headline())));
        expenseAnalyticsService.analyze(ownerId, from, to).days().stream()
                .filter(day -> day.total().signum() > 0)
                .forEach(day -> summaries.compute(day.date(), (date, current) -> current == null
                        ? new DailyDaySummary(date, day.financeExpenseCount(), 0, 0, 0,
                        day.total(), day.financeHeadline())
                        : new DailyDaySummary(date, current.eventCount() + day.financeExpenseCount(),
                        current.todoCount(), current.completedTodoCount(), current.scheduleCount(),
                        day.total(), current.headline())));
        var itemsByDate = repository.findCalendarItems(ownerId, from, to, LocalDate.now()).stream()
                .collect(Collectors.groupingBy(CalendarItem::date));
        return summaries.values().stream().sorted(java.util.Comparator.comparing(DailyDaySummary::date))
                .map(day -> new DailyDaySummary(day.date(), day.eventCount(), day.todoCount(), day.completedTodoCount(),
                        day.scheduleCount(), day.expenseTotal(), day.headline(),
                        itemsByDate.getOrDefault(day.date(), List.of()))).toList();
    }

    /** 读取所选日期所在自然周和自然月的真实周期待办。 */
    @Transactional(readOnly = true)
    public List<RecurringTodo> getRecurringTodos(String ownerId, LocalDate focusDate) {
        if (ownerId == null || ownerId.isBlank() || focusDate == null) {
            throw new IllegalArgumentException("用户与日期不能为空");
        }
        LocalDate weekStart = focusDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        LocalDate monthStart = focusDate.withDayOfMonth(1);
        LocalDate monthEnd = focusDate.withDayOfMonth(focusDate.lengthOfMonth());
        return repository.findRecurringTodos(ownerId, weekStart, weekEnd, monthStart, monthEnd).stream()
                .map(row -> {
                    LocalDate occurrenceDate;
                    if ("weekly".equals(row.recurrence())) {
                        if (row.recurrenceWeekday() != null) {
                            occurrenceDate = weekStart.plusDays(row.recurrenceWeekday() - 1L);
                        } else if (row.completed() && row.completedDate() != null) {
                            occurrenceDate = row.completedDate();
                        } else {
                            LocalDate today = LocalDate.now();
                            occurrenceDate = (!today.isBefore(weekStart) && !today.isAfter(weekEnd)) ? today : weekStart;
                        }
                    } else {
                        if (row.recurrenceMonthDay() != null) {
                            occurrenceDate = monthStart.plusDays(Math.min(row.recurrenceMonthDay(), focusDate.lengthOfMonth()) - 1L);
                        } else if (row.completed() && row.completedDate() != null) {
                            occurrenceDate = row.completedDate();
                        } else {
                            LocalDate today = LocalDate.now();
                            occurrenceDate = (!today.isBefore(monthStart) && !today.isAfter(monthEnd)) ? today : monthStart;
                        }
                    }
                    if (!row.completed()) {
                        LocalDate periodStart = "weekly".equals(row.recurrence()) ? weekStart : monthStart;
                        if (occurrenceDate == null || occurrenceDate.isBefore(periodStart)) {
                            occurrenceDate = periodStart;
                        }
                    }
                    return new RecurringTodo(
                            row.id(), row.title(), row.version(), row.recurrence(), occurrenceDate, row.completed());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 使用乐观版本检查修改待办完成状态。 */
    @Transactional
    public DailyEvent setTodoCompleted(
            String ownerId, String eventId, boolean completed, int expectedVersion, LocalDate occurrenceDate) {
        DailyEventRow row = requireEvent(ownerId, eventId);
        if (!"todo".equals(row.eventType())) throw new IllegalArgumentException("指定日记录不是待办");
        TodoTypeHandler.TodoRecurrenceRule recurrenceRule = todoTypeHandler.findRecurrenceRule(eventId);
        LocalDate effectiveDate = occurrenceDate == null ? row.eventDate() : occurrenceDate;
        if (!todoTypeHandler.occursOn(row.eventDate(), effectiveDate, recurrenceRule)) {
            throw new IllegalArgumentException("待办完成日期不属于该待办");
        }
        todoTypeHandler.setCompleted(eventId, effectiveDate, recurrenceRule.recurrence(), completed);
        if (!repository.advanceVersion(ownerId, eventId, expectedVersion, Instant.now())) {
            throw new IllegalStateException("日记录已被其他操作修改，请刷新后重试");
        }
        return getDay(ownerId, effectiveDate).events().stream()
                .filter(event -> event.id().equals(eventId))
                .findFirst()
                .orElseGet(() -> getEventDefinition(ownerId, eventId));
    }

    /** 兼容领域内未传出现日期的旧调用，普通待办仍以创建日期作为完成周期。 */
    public DailyEvent setTodoCompleted(String ownerId, String eventId, boolean completed, int expectedVersion) {
        return setTodoCompleted(ownerId, eventId, completed, expectedVersion, null);
    }

    /** 使用乐观版本检查删除日记录。 */
    @Transactional
    public void delete(String ownerId, String eventId, int expectedVersion) {
        DailyEventRow row = requireEvent(ownerId, eventId);
        if (!repository.delete(ownerId, eventId, expectedVersion)) {
            throw new IllegalStateException("日记录已被其他操作修改，请刷新后重试");
        }
        if ("journal".equals(row.eventType()) && "manual".equals(row.source())) {
            recordJournalProjectionService.removeFromCalendar(ownerId, eventId);
        }
    }

    /** 更新现有手记，避免编辑操作产生重复记录。 */
    @Transactional
    public DailyEvent updateJournal(
            String ownerId, String eventId, int expectedVersion, JournalCommand command) {
        return update(ownerId, eventId, expectedVersion, command);
    }

    /** 通过类型处理器更新任意已注册日记录。 */
    @Transactional
    public DailyEvent update(
            String ownerId, String eventId, int expectedVersion, DailyEventCommand command) {
        validate(ownerId, command);
        DailyEventRow row = requireEvent(ownerId, eventId);
        if (!row.eventType().equals(command.eventType())) throw new IllegalArgumentException("日记录类型不能变更");
        typeRegistry.update(eventId, command);
        String title = command.title() == null || command.title().isBlank() ? "无标题记录" : command.title().trim();
        if (!repository.updateEvent(ownerId, eventId, expectedVersion, command.eventDate(), title, Instant.now())) {
            throw new IllegalStateException("日记录已被其他操作修改，请刷新后重试");
        }
        if (command instanceof JournalCommand journal && "manual".equals(row.source())) {
            recordJournalProjectionService.upsertFromCalendar(ownerId, eventId, journal.eventDate(), title, journal.body());
        }
        return readStoredEvent(ownerId, eventId, command.eventDate());
    }

    /** 读取刚写入的稳定记录，不要求周期待办在生效日当天发生。 */
    private DailyEvent readStoredEvent(String ownerId, String eventId, LocalDate occurrenceDate) {
        DailyEventRow row = requireEvent(ownerId, eventId);
        Object details = "todo".equals(row.eventType())
                ? todoTypeHandler.loadDetailsForOccurrence(List.of(eventId), occurrenceDate).get(eventId)
                : typeRegistry.loadDetails(Map.of(row.eventType(), List.of(eventId))).get(eventId);
        return new DailyEvent(
                row.id(), row.eventDate(), row.eventType(), row.title(), row.source(), row.status(),
                row.version(), row.createdAt(), row.updatedAt(), details);
    }

    private DailyEventRow requireEvent(String ownerId, String eventId) {
        if (ownerId == null || ownerId.isBlank() || eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("用户与日记录 ID 不能为空");
        }
        return repository.findById(ownerId, eventId)
                .orElseThrow(() -> new IllegalArgumentException("日记录不存在"));
    }

    private void validate(String ownerId, DailyEventCommand command) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (command == null || command.eventDate() == null) throw new IllegalArgumentException("日记录日期不能为空");
    }
}
