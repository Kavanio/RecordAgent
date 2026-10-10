package butvan.agent.network.agenttool.calendar;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.tool.result.ToolResult;
import butvan.agent.network.agenttool.calendar.CalendarTool.CalendarItem;
import butvan.agent.network.agenttool.calendar.CalendarTool.CompletionRequest;
import butvan.agent.network.agenttool.calendar.CalendarTool.CreateRequest;
import butvan.agent.network.agenttool.calendar.CalendarTool.DeleteRequest;
import butvan.agent.network.agenttool.calendar.CalendarTool.QueryRequest;
import butvan.agent.network.agenttool.calendar.CalendarTool.QueryResult;
import butvan.agent.network.agenttool.calendar.CalendarTool.RecurrenceInput;
import butvan.agent.network.agenttool.calendar.CalendarTool.UpdateRequest;
import butvan.agent.network.agenttool.common.BusinessToolExecutor;
import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = {
        LocalDatabaseConfiguration.class,
        JacksonAutoConfiguration.class,
        DailyEventModuleConfiguration.class,
        BusinessToolExecutor.class,
        CurrentUserProvider.class,
        CalendarTool.class
})
class CalendarToolIntegrationTest {
    private static final Path DATABASE_PATH = createDatabasePath();

    @Autowired
    private CalendarTool calendarTool;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void exposesAllCalendarToolSchemas() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(calendarTool);

        assertEquals(Set.of(
                "calendar_query", "calendar_create", "calendar_update",
                "calendar_set_todo_completed", "calendar_delete"), toolkit.getToolNames());
        assertTrue(toolkit.getToolSchemas().stream()
                .filter(schema -> schema.getName().equals("calendar_create"))
                .findFirst().orElseThrow().getParameters().containsKey("properties"));
    }

    @Test
    void weeklyTodoCanBeCreatedQueriedCompletedUpdatedAndDeleted() {
        ToolResult<?> createdResult = calendarTool.create(new CreateRequest(
                "todo", "2026-09-01", "提交周报", "09:00", "high",
                new RecurrenceInput("weekly", 5, null),
                null, null, null, null, "calendar-weekly-create"));
        assertTrue(createdResult.success());
        CalendarItem created = assertInstanceOf(CalendarItem.class, createdResult.data());
        assertEquals("2026-09-01", created.startsOn().toString());
        assertEquals("2026-09-04", created.occurrenceDate().toString());

        ToolResult<?> queriedResult = calendarTool.query(new QueryRequest(
                "2026-09-01", "2026-09-11", List.of("todo"), null, "周报", 20));
        QueryResult queried = assertInstanceOf(QueryResult.class, queriedResult.data());
        assertEquals(List.of("2026-09-04", "2026-09-11"), queried.items().stream()
                .map(item -> item.occurrenceDate().toString()).toList());

        ToolResult<?> wrongDay = calendarTool.setTodoCompleted(new CompletionRequest(
                created.id(), "2026-09-03", true, created.version(), "calendar-complete-wrong-day"));
        assertFalse(wrongDay.success());
        assertEquals("INVALID_ARGUMENT", wrongDay.error().code());

        ToolResult<?> completedResult = calendarTool.setTodoCompleted(new CompletionRequest(
                created.id(), "2026-09-04", true, created.version(), "calendar-complete-friday"));
        CalendarItem completed = assertInstanceOf(CalendarItem.class, completedResult.data());
        assertTrue(completed.todo().completed());

        ToolResult<?> updatedResult = calendarTool.update(new UpdateRequest(
                created.id(), completed.version(), null, "提交本周周报", null, null,
                null, null, null, null, null, "calendar-update-title"));
        CalendarItem updated = assertInstanceOf(CalendarItem.class, updatedResult.data());
        assertEquals("weekly", updated.todo().recurrence().type());
        assertEquals(5, updated.todo().recurrence().weekday());

        ToolResult<?> deletedResult = calendarTool.delete(new DeleteRequest(
                created.id(), updated.version(), "calendar-delete-series"));
        assertTrue(deletedResult.success());
        QueryResult afterDelete = assertInstanceOf(QueryResult.class, calendarTool.query(new QueryRequest(
                "2026-09-01", "2026-09-11", List.of("todo"), null, null, 20)).data());
        assertTrue(afterDelete.items().isEmpty());
    }

    @Test
    void repeatedCreateKeyDoesNotCreateDuplicateTodo() {
        CreateRequest request = new CreateRequest(
                "todo", "2026-10-01", "幂等待办", null, "medium",
                new RecurrenceInput("none", null, null),
                null, null, null, null, "calendar-idempotent-create");

        assertTrue(calendarTool.create(request).success());
        assertTrue(calendarTool.create(request).success());

        QueryResult result = assertInstanceOf(QueryResult.class, calendarTool.query(new QueryRequest(
                "2026-10-01", "2026-10-01", List.of("todo"), null, "幂等待办", 20)).data());
        assertEquals(1, result.items().size());
    }

    @Test
    void weeklyAndMonthlyCanBeUnspecifiedOrSpecific() {
        ToolResult<?> unspecifiedWeekly = calendarTool.create(new CreateRequest(
                "todo", "2026-11-01", "每周任务", null, "medium",
                new RecurrenceInput("weekly", null, null),
                null, null, null, null, "calendar-unspecified-weekly"));
        ToolResult<?> unspecifiedMonthly = calendarTool.create(new CreateRequest(
                "todo", "2026-11-01", "每月任务", null, "medium",
                new RecurrenceInput("monthly", null, null),
                null, null, null, null, "calendar-unspecified-month-day"));
        ToolResult<?> invalidWeekly = calendarTool.create(new CreateRequest(
                "todo", "2026-11-01", "非法周任务", null, "medium",
                new RecurrenceInput("weekly", 8, null),
                null, null, null, null, "calendar-invalid-weekday"));
        ToolResult<?> invalidMonthly = calendarTool.create(new CreateRequest(
                "todo", "2026-11-01", "非法月任务", null, "medium",
                new RecurrenceInput("monthly", null, 32),
                null, null, null, null, "calendar-invalid-month-day"));

        assertTrue(unspecifiedWeekly.success());
        assertEquals(null, assertInstanceOf(CalendarItem.class, unspecifiedWeekly.data()).todo().recurrence().weekday());

        assertTrue(unspecifiedMonthly.success());
        assertEquals(null, assertInstanceOf(CalendarItem.class, unspecifiedMonthly.data()).todo().recurrence().dayOfMonth());

        assertEquals("INVALID_ARGUMENT", invalidWeekly.error().code());
        assertEquals("INVALID_ARGUMENT", invalidMonthly.error().code());
    }

    private static Path createDatabasePath() {
        try {
            Path directory = Files.createTempDirectory("butvan-calendar-tool-test-");
            return directory.resolve("butvan-test.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试数据库目录", exception);
        }
    }
}
