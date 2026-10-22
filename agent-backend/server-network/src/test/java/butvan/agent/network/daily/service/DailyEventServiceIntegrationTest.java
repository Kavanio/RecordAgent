package butvan.agent.network.daily.service;

import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import butvan.agent.network.daily.model.DailyEventModels.DailyEvent;
import butvan.agent.network.daily.model.DailyEventModels.ExpenseCommand;
import butvan.agent.network.daily.model.DailyEventModels.JournalCommand;
import butvan.agent.network.daily.model.DailyEventModels.JournalDetails;
import butvan.agent.network.daily.model.DailyEventModels.ScheduleCommand;
import butvan.agent.network.daily.model.DailyEventModels.ScheduleDetails;
import butvan.agent.network.daily.model.DailyEventModels.TodoCommand;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 通过公开领域接口和真实临时 SQLite 验证日记录行为。 */
@SpringBootTest(classes = {LocalDatabaseConfiguration.class, DailyEventModuleConfiguration.class})
class DailyEventServiceIntegrationTest {

    private static final Path DATABASE_PATH = createDatabasePath();

    @Autowired
    private DailyEventService dailyEventService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void calendarItemsAreBoundedOwnedAndDoNotProjectRecurringTodosIntoFuture() {
        LocalDate today = LocalDate.now();
        String owner = "calendar-items-user";
        var recurring = dailyEventService.create(owner,
                new TodoCommand(today.minusDays(1), "每日学习", null, "medium", "daily"));
        for (int i = 0; i < 5; i++) {
            dailyEventService.create(owner, new TodoCommand(today, "事项" + i, null, "medium"));
        }
        dailyEventService.create("other-calendar-owner", new TodoCommand(today, "其他用户的事项", null, "medium"));
        var days = dailyEventService.getDays(owner, today, today.plusDays(1));
        var summary = days.stream().filter(day -> day.date().equals(today)).findFirst().orElseThrow();
        assertEquals(4, summary.items().size());
        assertEquals(recurring.id(), summary.items().getFirst().id());
        assertTrue(summary.items().stream().noneMatch(item -> item.title().equals("其他用户的事项")));
        assertTrue(days.stream().noneMatch(day -> day.date().equals(today.plusDays(1))));
    }

    @Test
    void minimalTodoOccurrencesMatchCalendarRulesAcrossCyclesAndOwners() {
        String owner = "minimal-todo-owner";
        LocalDate start = LocalDate.of(2026, 9, 7);
        dailyEventService.create(owner, new TodoCommand(start, "一次性", null, "medium"));
        dailyEventService.create(owner, new TodoCommand(start, "每日", null, "medium", "daily"));
        dailyEventService.create(owner, new TodoCommand(start, "每周一", null, "medium", "weekly", 1, null));
        dailyEventService.create(owner, new TodoCommand(start, "月末", null, "medium", "monthly", null, 31));
        var legacyWeek = dailyEventService.create(owner, new TodoCommand(start, "旧周规则", null, "medium", "weekly"));
        var legacyMonth = dailyEventService.create(owner, new TodoCommand(start, "旧月规则", null, "medium", "monthly"));
        dailyEventService.setTodoCompleted(owner, legacyWeek.id(), true, legacyWeek.version(), start.plusDays(1));
        dailyEventService.setTodoCompleted(owner, legacyMonth.id(), true, legacyMonth.version(), start.plusDays(2));
        dailyEventService.create(owner, new JournalCommand(start, "私密手记", "不应被统计读取的正文", "平静"));
        dailyEventService.create("other-minimal-owner", new TodoCommand(start, "其他用户", null, "medium", "daily"));
        for (LocalDate day = start; !day.isAfter(start.plusDays(28)); day = day.plusDays(1)) {
            var expected = dailyEventService.getDay(owner, day).events().stream()
                    .filter(event -> event.details() instanceof TodoDetails)
                    .map(event -> new butvan.agent.network.daily.model.DailyEventModels.TodoOccurrence(
                            event.id(), ((TodoDetails) event.details()).recurrence(), ((TodoDetails) event.details()).completed())).toList();
            assertEquals(expected, dailyEventService.getTodoOccurrences(owner, day), "发生规则和完成状态必须一致：" + day);
        }
        assertTrue(dailyEventService.getTodoOccurrences("no-such-owner", start).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> dailyEventService.getTodoOccurrences("", start));
        assertThrows(IllegalArgumentException.class, () -> dailyEventService.getTodoOccurrences(owner, null));
    }

    @Test
    void userCanCreateAndRetrieveTodoForOneDay() {
        LocalDate date = LocalDate.of(2026, 9, 3);

        DailyEvent created = dailyEventService.create(
                "local-default",
                new TodoCommand(date, "整理学习笔记", "20:30", "high"));

        var day = dailyEventService.getDay("local-default", date);
        assertEquals(1, day.events().size());
        assertEquals(created.id(), day.events().getFirst().id());
        TodoDetails details = assertInstanceOf(TodoDetails.class, day.events().getFirst().details());
        assertEquals("20:30", details.time());
        assertEquals("high", details.priority());
        assertFalse(details.completed());
    }

    @Test
    void dayCanAggregateIndependentRecordTypesAndProduceRangeSummary() {
        LocalDate date = LocalDate.of(2026, 9, 4);
        dailyEventService.create("local-default",
                new ScheduleCommand(date, "项目评审", "09:30", "10:30", "线上", ZoneId.of("Asia/Shanghai")));
        dailyEventService.create("local-default",
                new ExpenseCommand(date, "餐饮", "午餐", new BigDecimal("26.80"), "12:10", "CNY"));
        dailyEventService.create("local-default",
                new JournalCommand(date, "今天的复盘", "日记录模型已经跑通。", "充实"));

        var day = dailyEventService.getDay("local-default", date);
        Set<String> types = day.events().stream().map(DailyEvent::eventType).collect(Collectors.toSet());
        assertEquals(Set.of("schedule", "expense", "journal"), types);

        var summaries = dailyEventService.getDays(
                "local-default", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        var summary = summaries.stream().filter(item -> item.date().equals(date)).findFirst().orElseThrow();
        assertEquals(3, summary.eventCount());
        assertEquals(new BigDecimal("26.80"), summary.expenseTotal());
        assertEquals(1, summary.scheduleCount());
    }

    @Test
    void todoCanBeCompletedWithVersionCheckAndDeleted() {
        LocalDate date = LocalDate.of(2026, 9, 5);
        DailyEvent created = dailyEventService.create(
                "local-default", new TodoCommand(date, "完成数据库验收", null, "medium"));

        DailyEvent completed = dailyEventService.setTodoCompleted(
                "local-default", created.id(), true, created.version());
        assertEquals(1, completed.version());
        assertTrue(assertInstanceOf(TodoDetails.class, completed.details()).completed());
        assertNotNull(assertInstanceOf(TodoDetails.class, completed.details()).completedAt());

        dailyEventService.delete("local-default", completed.id(), completed.version());
        assertTrue(dailyEventService.getDay("local-default", date).events().isEmpty());
    }

    @Test
    void recurringTodosAppearOnTheirConfiguredWeekdayOrNaturalMonthEnd() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        DailyEvent daily = dailyEventService.create(
                "recurring-user", new TodoCommand(start, "学习一个知识点", null, "medium", "daily"));
        DailyEvent weekly = dailyEventService.create(
                "recurring-user", new TodoCommand(start, "完成一次复盘", null, "high", "weekly", 3, null));
        DailyEvent monthly = dailyEventService.create(
                "recurring-user", new TodoCommand(start, "读完一本书", null, "low", "monthly", null, 31));

        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        DailyEvent completedWeekly = dailyEventService.setTodoCompleted(
                "recurring-user", weekly.id(), true, weekly.version(), wednesday);
        dailyEventService.setTodoCompleted(
                "recurring-user", daily.id(), true, daily.version(), wednesday);

        TodoDetails weeklyDetails = assertInstanceOf(TodoDetails.class, completedWeekly.details());
        assertEquals("weekly", weeklyDetails.recurrence());
        assertEquals(3, weeklyDetails.recurrenceWeekday());
        assertEquals(null, weeklyDetails.recurrenceMonthDay());

        var thursday = dailyEventService.getDay("recurring-user", LocalDate.of(2026, 9, 3));
        assertEquals(List.of(daily.id()), thursday.events().stream().map(DailyEvent::id).toList());

        LocalDate monthDay = LocalDate.of(2026, 9, 30);
        var monthEnd = dailyEventService.getDay("recurring-user", monthDay);
        assertEquals(Set.of(daily.id(), weekly.id(), monthly.id()),
                monthEnd.events().stream().map(DailyEvent::id).collect(Collectors.toSet()));
        DailyEvent completedMonthly = dailyEventService.setTodoCompleted(
                "recurring-user", monthly.id(), true, monthly.version(), monthDay);
        assertEquals(31, assertInstanceOf(TodoDetails.class, completedMonthly.details()).recurrenceMonthDay());
        assertFalse(todoDetails(
                dailyEventService.getDay("recurring-user", LocalDate.of(2026, 10, 31)).events(), monthly.id()).completed());

        LocalDate nextWednesday = LocalDate.of(2026, 9, 9);
        var nextWeek = dailyEventService.getDay("recurring-user", nextWednesday);
        assertFalse(todoDetails(nextWeek.events(), weekly.id()).completed());
        assertEquals(Set.of(daily.id(), weekly.id()), nextWeek.events().stream().map(DailyEvent::id).collect(Collectors.toSet()));

        var summaries = dailyEventService.getDays("recurring-user", start, monthDay);
        assertEquals(1, summaries.stream().filter(item -> item.date().equals(start)).findFirst().orElseThrow().todoCount());
        assertEquals(2, summaries.stream().filter(item -> item.date().equals(wednesday)).findFirst().orElseThrow().todoCount());
        var monthEndSummary = summaries.stream().filter(item -> item.date().equals(monthDay)).findFirst().orElseThrow();
        assertEquals(3, monthEndSummary.todoCount());
        assertEquals(2, summaries.stream().filter(item -> item.date().equals(nextWednesday)).findFirst().orElseThrow().todoCount());
    }

    @Test
    void recurringTodoNotesUseRealDefinitionsAndPeriodCompletion() {
        String owner = "recurring-note-user";
        LocalDate focus = LocalDate.of(2026, 9, 22);
        // 不固定日期的周待办和月待办（属于便签）
        DailyEvent weekly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 9, 1), "完成周复盘", null, "high", "weekly", null, null));
        DailyEvent monthly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 8, 1), "整理月度资料", null, "medium", "monthly", null, null));
        // 指定了固定星期五的周待办与指定了31号的月待办（属于日历日程，不应进入便签）
        DailyEvent fixedWeekly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 9, 1), "周五固定例会", null, "high", "weekly", 5, null));
        DailyEvent fixedMonthly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 8, 1), "月末固定对账", null, "medium", "monthly", null, 31));
        dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 9, 1), "普通待办", null, "low"));

        var firstRead = dailyEventService.getRecurringTodos(owner, focus);
        assertEquals(2, firstRead.size());
        assertTrue(firstRead.stream().anyMatch(item -> item.id().equals(weekly.id())));
        assertTrue(firstRead.stream().anyMatch(item -> item.id().equals(monthly.id())));
        assertFalse(firstRead.stream().anyMatch(item -> item.id().equals(fixedWeekly.id())));
        assertFalse(firstRead.stream().anyMatch(item -> item.id().equals(fixedMonthly.id())));
        assertFalse(firstRead.getFirst().completed());

        dailyEventService.setTodoCompleted(owner, weekly.id(), true, weekly.version(), LocalDate.of(2026, 9, 25));
        var completedRead = dailyEventService.getRecurringTodos(owner, focus);
        var completedWeekly = completedRead.stream().filter(item -> item.id().equals(weekly.id())).findFirst().orElseThrow();
        assertTrue(completedWeekly.completed());
        assertEquals(LocalDate.of(2026, 9, 25), completedWeekly.occurrenceDate());
    }

    @Test
    void monthlyTodoUsesTheActualLastDayOfEachMonth() {
        String owner = "month-end-user";
        DailyEvent monthly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 1, 30), "月末复盘", null, "medium", "monthly", null, 31));

        assertEquals(List.of(monthly.id()), dailyEventService.getDay(owner, LocalDate.of(2026, 1, 31))
                .events().stream().map(DailyEvent::id).toList());
        assertEquals(List.of(monthly.id()), dailyEventService.getDay(owner, LocalDate.of(2026, 2, 28))
                .events().stream().map(DailyEvent::id).toList());
        assertTrue(dailyEventService.getDay(owner, LocalDate.of(2026, 2, 27)).events().isEmpty());
    }

    @Test
    void unspecifiedRecurringTodosOnlyAppearInStickyNotesUntilCompletedOnThatDay() {
        String owner = "unspecified-recurring-user";
        LocalDate start = LocalDate.of(2026, 9, 1);
        DailyEvent weekly = dailyEventService.create(
                owner, new TodoCommand(start, "自由周任务", null, "high", "weekly", null, null));
        DailyEvent monthly = dailyEventService.create(
                owner, new TodoCommand(start, "自由月任务", null, "medium", "monthly", null, null));

        LocalDate monday = LocalDate.of(2026, 9, 14);
        LocalDate tuesday = LocalDate.of(2026, 9, 15);
        LocalDate wednesday = LocalDate.of(2026, 9, 16);

        // 未勾选时，便签可见，但日历日记录和月摘要均不出现
        var notes = dailyEventService.getRecurringTodos(owner, tuesday);
        assertEquals(2, notes.size());
        assertFalse(notes.get(0).completed());
        assertFalse(notes.get(1).completed());

        assertTrue(dailyEventService.getDay(owner, monday).events().isEmpty());
        assertTrue(dailyEventService.getDay(owner, tuesday).events().isEmpty());
        assertTrue(dailyEventService.getDay(owner, wednesday).events().isEmpty());

        var initialSummaries = dailyEventService.getDays(owner, monday, wednesday);
        assertTrue(initialSummaries.stream().allMatch(s -> s.todoCount() == 0));

        // 在周二（9月15日）勾选完成周待办与月待办
        dailyEventService.setTodoCompleted(owner, weekly.id(), true, weekly.version(), tuesday);
        dailyEventService.setTodoCompleted(owner, monthly.id(), true, monthly.version(), tuesday);

        // 周二日记录中展示两条已完成待办，而周一、周三依然为空
        var tuesdayEvents = dailyEventService.getDay(owner, tuesday).events();
        assertEquals(Set.of(weekly.id(), monthly.id()),
                tuesdayEvents.stream().map(DailyEvent::id).collect(Collectors.toSet()));
        assertTrue(tuesdayEvents.stream().allMatch(e -> ((TodoDetails) e.details()).completed()));

        assertTrue(dailyEventService.getDay(owner, monday).events().isEmpty());
        assertTrue(dailyEventService.getDay(owner, wednesday).events().isEmpty());

        var completedSummaries = dailyEventService.getDays(owner, monday, wednesday);
        var tuesdaySummary = completedSummaries.stream().filter(s -> s.date().equals(tuesday)).findFirst().orElseThrow();
        assertEquals(2, tuesdaySummary.todoCount());
        assertEquals(2, tuesdaySummary.completedTodoCount());

        // 取消勾选周待办
        dailyEventService.setTodoCompleted(owner, weekly.id(), false, weekly.version() + 1, tuesday);
        var afterUncheckEvents = dailyEventService.getDay(owner, tuesday).events();
        assertEquals(List.of(monthly.id()), afterUncheckEvents.stream().map(DailyEvent::id).toList());
    }

    @Test
    void recurringTodoCreatedMidMonthCanBeCompletedOnEarlierDateInSamePeriod() {
        String owner = "mid-month-recurring-user";
        // 在 10月14日 创建一个月待办与周待办（例如日历选在14号创建的便签待办）
        DailyEvent monthly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 10, 14), "月中创建的月待办", null, "medium", "monthly", null, null));
        DailyEvent weekly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2026, 10, 14), "周中创建的周待办", null, "medium", "weekly", null, null));

        // 用户在 10月11日（属于同一个10月份，但日期早于 14号）在便签中勾选完成
        LocalDate oct11 = LocalDate.of(2026, 10, 11);
        dailyEventService.setTodoCompleted(owner, monthly.id(), true, monthly.version(), oct11);
        // 验证 10月11日 日记录中正确投影展示了该月待办
        var oct11Events = dailyEventService.getDay(owner, oct11).events();
        assertTrue(oct11Events.stream().anyMatch(e -> e.id().equals(monthly.id())));

        // 验证便签查询状态
        var notes = dailyEventService.getRecurringTodos(owner, oct11);
        var completedMonthly = notes.stream().filter(n -> n.id().equals(monthly.id())).findFirst().orElseThrow();
        assertTrue(completedMonthly.completed());
        assertEquals(oct11, completedMonthly.occurrenceDate());

        // 周待办在同周周三创建，在同周周一（10月12日）也能顺利完成
        LocalDate oct12 = LocalDate.of(2026, 10, 12);
        dailyEventService.setTodoCompleted(owner, weekly.id(), true, weekly.version(), oct12);
        var oct12Events = dailyEventService.getDay(owner, oct12).events();
        assertTrue(oct12Events.stream().anyMatch(e -> e.id().equals(weekly.id())));
    }

    @Test
    void changingTodoRecurrenceClearsCompletionFromTheOldPeriodRule() {
        String owner = "recurrence-change-user";
        LocalDate septemberEnd = LocalDate.of(2025, 9, 30);
        DailyEvent monthly = dailyEventService.create(
                owner, new TodoCommand(LocalDate.of(2025, 9, 1), "周期复盘", null, "medium", "monthly", null, null));
        DailyEvent completed = dailyEventService.setTodoCompleted(
                owner, monthly.id(), true, monthly.version(), septemberEnd);

        DailyEvent updated = dailyEventService.update(
                owner, monthly.id(), completed.version(),
                new TodoCommand(LocalDate.of(2025, 9, 1), "周期复盘", null, "medium", "weekly", 2, null));

        assertFalse(todoDetails(
                dailyEventService.getDay(owner, LocalDate.of(2025, 9, 2)).events(), updated.id()).completed());
    }

    @Test
    void recurringTodoRejectsCompletionOutsideConfiguredDate() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        DailyEvent weekly = dailyEventService.create(
                "recurring-validation-user", new TodoCommand(start, "每周复盘", null, "high", "weekly", 5, null));

        assertThrows(IllegalArgumentException.class, () -> dailyEventService.setTodoCompleted(
                "recurring-validation-user", weekly.id(), true, weekly.version(), LocalDate.of(2026, 9, 2)));
        assertThrows(IllegalArgumentException.class, () -> dailyEventService.create(
                "recurring-validation-user", new TodoCommand(start, "错误日期", null, "low", "monthly", null, 32)));
    }

    @Test
    void scheduleCanBeCreatedWithoutStartOrEndTime() {
        LocalDate date = LocalDate.of(2026, 9, 10);
        DailyEvent created = dailyEventService.create(
                "optional-time-user", new ScheduleCommand(date, "等待确认的日程", null, null, null,
                        ZoneId.of("Asia/Shanghai")));

        ScheduleDetails details = assertInstanceOf(ScheduleDetails.class, created.details());
        assertEquals(null, details.startTime());
        assertEquals(null, details.endTime());
    }

    @Test
    void recurringTodoSummaryDoesNotProjectPendingWorkIntoFutureDays() {
        LocalDate today = LocalDate.now();
        dailyEventService.create(
                "recurring-cutoff-user", new TodoCommand(today, "每天学习", null, "medium", "daily"));

        var summaries = dailyEventService.getDays("recurring-cutoff-user", today, today.plusDays(3));

        assertEquals(1, summaries.size());
        assertEquals(today, summaries.getFirst().date());
        assertEquals(1, summaries.getFirst().todoCount());
    }

    @Test
    void journalCanBeUpdatedWithoutCreatingAnotherDailyEvent() {
        LocalDate date = LocalDate.of(2026, 9, 7);
        DailyEvent created = dailyEventService.create(
                "local-default", new JournalCommand(date, "初稿", "第一版正文", "平静"));

        DailyEvent updated = dailyEventService.updateJournal(
                "local-default", created.id(), created.version(),
                new JournalCommand(date, "复盘", "已经补充完整。", "充实"));

        assertEquals(1, updated.version());
        assertEquals("复盘", updated.title());
        assertEquals("已经补充完整。", assertInstanceOf(JournalDetails.class, updated.details()).body());
        assertEquals(1, dailyEventService.getDay("local-default", date).events().size());
    }

    @Test
    void structuredRecordTypesCanBeEditedThroughTheSameStableInterface() {
        LocalDate date = LocalDate.of(2026, 9, 8);
        DailyEvent todo = dailyEventService.create(
                "local-default", new TodoCommand(date, "旧待办", "08:00", "low"));
        DailyEvent schedule = dailyEventService.create(
                "local-default", new ScheduleCommand(date, "旧日程", "09:00", "10:00", null, ZoneId.of("Asia/Shanghai")));
        DailyEvent expense = dailyEventService.create(
                "local-default", new ExpenseCommand(date, "餐饮", "旧花销", new BigDecimal("12.00"), "12:00", "CNY"));

        DailyEvent updatedTodo = dailyEventService.update(
                "local-default", todo.id(), todo.version(), new TodoCommand(date, "新待办", "08:30", "high"));
        DailyEvent updatedSchedule = dailyEventService.update(
                "local-default", schedule.id(), schedule.version(),
                new ScheduleCommand(date, "新日程", "10:00", "11:00", "会议室", ZoneId.of("Asia/Shanghai")));
        DailyEvent updatedExpense = dailyEventService.update(
                "local-default", expense.id(), expense.version(),
                new ExpenseCommand(date, "学习", "新花销", new BigDecimal("39.90"), "19:00", "CNY"));

        assertEquals(todo.id(), updatedTodo.id());
        assertEquals("新待办", updatedTodo.title());
        assertEquals(schedule.id(), updatedSchedule.id());
        assertEquals("新日程", updatedSchedule.title());
        assertEquals(expense.id(), updatedExpense.id());
        assertEquals("新花销", updatedExpense.title());
        assertEquals(1, updatedTodo.version());
        assertEquals(1, updatedSchedule.version());
        assertEquals(1, updatedExpense.version());
    }

    @Test
    void concurrentLocalWritesAreRetriedWithoutLosingDailyEvents() throws Exception {
        LocalDate date = LocalDate.of(2026, 9, 9);
        var writes = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> (Callable<DailyEvent>) () -> dailyEventService.create(
                        "local-default", new TodoCommand(date, "并发待办 " + index, null, "medium")))
                .toList();

        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(writes)) result.get();
        }

        assertEquals(8, dailyEventService.getDay("local-default", date).events().size());
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-daily-event-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建日记录测试数据库目录", exception);
        }
    }

    private static TodoDetails todoDetails(java.util.List<DailyEvent> events, String eventId) {
        return events.stream()
                .filter(event -> event.id().equals(eventId))
                .map(DailyEvent::details)
                .map(TodoDetails.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
