package butvan.agent.network.review;

import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.daily.repository.DailyEventRepository;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.daily.type.DailyEventTypeRegistry;
import butvan.agent.network.daily.type.TodoTypeHandler;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.record.service.RecordJournalProjectionService;
import butvan.agent.network.review.service.ReviewFactCollector;
import butvan.agent.network.study.service.StudyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用真实日记录 Service 验证最小化调用链，避免整体 Mock Service 掩盖跨域读取。 */
class ReviewTodoIsolationTest {
    private final LocalDate day = LocalDate.of(2026, 9, 7);
    private final String owner = "synthetic-todo-owner";
    private DailyEventRepository repository;
    private DailyEventTypeRegistry registry;
    private TodoTypeHandler todos;
    private ExpenseAnalyticsService expenses;
    private FinanceService finance;
    private StudyService study;
    private RecordJournalProjectionService journals;
    private ReviewFactCollector collector;

    @BeforeEach void setup() {
        repository = mock(DailyEventRepository.class);
        registry = mock(DailyEventTypeRegistry.class);
        todos = mock(TodoTypeHandler.class);
        expenses = mock(ExpenseAnalyticsService.class);
        finance = mock(FinanceService.class);
        study = mock(StudyService.class);
        journals = mock(RecordJournalProjectionService.class);
        var daily = new DailyEventService(repository, registry, todos, expenses, journals);
        collector = new ReviewFactCollector(finance, expenses, daily, study);
        when(repository.findTodoIdsForDay(eq(owner), any())).thenReturn(List.of("daily", "weekly", "monthly"));
        when(todos.loadDetailsForOccurrence(anyList(), any())).thenReturn(Map.of(
                "daily", new TodoDetails(null, "medium", false, "daily", null, null),
                "weekly", new TodoDetails(null, "medium", true, "weekly", null, null),
                "monthly", new TodoDetails(null, "medium", false, "monthly", null, null)));
        // 如意外退回全量日视图／详情加载，立即失败，不能仅检查最终输出是否脱敏。
        when(repository.findDay(anyString(), any())).thenThrow(new AssertionError("禁止全量日记录查询"));
        when(registry.loadDetails(anyMap())).thenThrow(new AssertionError("禁止加载其他类型正文"));
    }

    @Test void todoOnlyReadsMinimalOwnedProjectionWithoutOtherDomains() {
        var result = collector.collect(owner, day, day, ZoneOffset.UTC, Set.of("TODO"));
        assertEquals("AVAILABLE", result.sources().getFirst().state());
        assertEquals(3, value(result, "occurrences"));
        assertEquals(1, value(result, "completed"));
        verify(repository).findTodoIdsForDay(owner, day);
        verify(todos).loadDetailsForOccurrence(List.of("daily", "weekly", "monthly"), day);
        verifyNoInteractions(registry, expenses, finance, study, journals);
        verify(repository, never()).findDay(anyString(), any());
    }

    @Test void financeFailureDoesNotMakeTodoUnavailable() {
        when(finance.getReportTransactions(eq(owner), any(), any())).thenThrow(new IllegalStateException("合成财务故障"));
        var result = collector.collect(owner, day, day, ZoneOffset.UTC, Set.of("FINANCE", "TODO"));
        assertTrue(result.sources().stream().anyMatch(s -> s.domain().equals("FINANCE") && s.state().equals("UNAVAILABLE")));
        assertTrue(result.sources().stream().anyMatch(s -> s.domain().equals("TODO") && s.state().equals("AVAILABLE")));
        assertEquals(3, value(result, "occurrences"));
        assertTrue(result.facts().stream().noneMatch(f -> f.domain().equals("FINANCE")));
        verifyNoInteractions(registry, expenses, journals);
    }

    @Test void recurringOccurrencesAreDeduplicatedWithinAndAcrossNaturalWeeks() {
        var first = collector.collect(owner, day, day.plusDays(6), ZoneOffset.UTC, Set.of("TODO"));
        assertEquals(9, value(first, "occurrences")); // 每日7、同一周1、同一月1。
        assertEquals(1, value(first, "completed"));
        var crossWeek = collector.collect(owner, day.plusDays(4), day.plusDays(10), ZoneOffset.UTC, Set.of("TODO"));
        assertEquals(10, value(crossWeek, "occurrences"));
        assertEquals(2, value(crossWeek, "completed"));
        verifyNoInteractions(registry, expenses, finance, study, journals);
    }

    @Test void missingTodoDetailsRemainUnavailableInsteadOfSilentlyCountingZero() {
        when(todos.loadDetailsForOccurrence(anyList(), any())).thenReturn(Map.of());
        var result = collector.collect(owner, day, day, ZoneOffset.UTC, Set.of("TODO"));
        assertEquals("UNAVAILABLE", result.sources().getFirst().state());
        assertTrue(result.facts().isEmpty());
    }

    private long value(ReviewFactCollector.Collected result, String key) {
        return result.facts().stream().filter(f -> f.domain().equals("TODO") && f.metricKey().equals(key))
                .findFirst().orElseThrow().value();
    }
}
