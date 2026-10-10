package com.butvan.recordagent.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.butvan.recordagent.config.BusinessProperties;
import com.butvan.recordagent.dto.calendar.CalendarDtos;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.calendar.TodoRepository;
import com.butvan.recordagent.service.calendar.impl.TodoServiceImpl;
import com.butvan.recordagent.service.query.ReadBounds;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 周期输入、发生日与完成键的回归测试；不访问真实用户数据库。 */
class TodoServiceImplTest {
    private final TodoRepository store = mock(TodoRepository.class);
    private final TodoServiceImpl service =
            new TodoServiceImpl(store, new ReadBounds(new BusinessProperties()));
    private final UUID owner = UUID.randomUUID();
    private final LocalDate monday = LocalDate.of(2026, 10, 5);

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7})
    void selectedWeekdayControlsCreationAndCompletion(int weekday) {
        var created = service.create(owner, request("weekly", weekday));
        verify(store)
                .create(
                        eq(created.id()),
                        eq(owner),
                        eq("待办"),
                        isNull(),
                        eq(monday),
                        isNull(),
                        eq("Asia/Shanghai"),
                        eq("weekly"),
                        eq(weekday));
        when(store.owned(owner, created.id()))
                .thenReturn(
                        Optional.of(
                                new TodoRepository.Row(
                                        created.id(),
                                        "待办",
                                        null,
                                        monday,
                                        null,
                                        "weekly",
                                        weekday,
                                        0)));
        LocalDate occurrence = monday.plusDays(weekday - 1);
        service.complete(
                owner, created.id(), occurrence, new CalendarDtos.CompleteOccurrence(true, 0L));
        verify(store).setCompleted(created.id(), monday, true);
        service.complete(
                owner,
                created.id(),
                occurrence.plusWeeks(1),
                new CalendarDtos.CompleteOccurrence(false, 0L));
        verify(store).setCompleted(created.id(), monday.plusWeeks(1), false);
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        owner,
                                        created.id(),
                                        occurrence.plusDays(1),
                                        new CalendarDtos.CompleteOccurrence(true, 0L)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ApiErrorCode.NOT_FOUND));
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        owner,
                                        created.id(),
                                        occurrence.minusWeeks(1),
                                        new CalendarDtos.CompleteOccurrence(true, 0L)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void omittedWeekdayKeepsLegacySundayAndInvalidRulesAreRejected() {
        var created = service.create(owner, request("weekly", null));
        verify(store)
                .create(
                        eq(created.id()),
                        eq(owner),
                        anyString(),
                        isNull(),
                        eq(monday),
                        isNull(),
                        anyString(),
                        eq("weekly"),
                        eq(7));
        for (int weekday : new int[] {0, 8}) {
            assertThatThrownBy(() -> service.create(owner, request("weekly", weekday)))
                    .isInstanceOf(ApiException.class);
        }
        for (String recurrence : List.of("none", "daily", "monthly")) {
            assertThatThrownBy(() -> service.create(owner, request(recurrence, 3)))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void monthlyHasNoTimeAndCompletesOnlyOnActualMonthEnd() {
        var created = service.create(owner, request("monthly", null));
        verify(store)
                .create(
                        eq(created.id()),
                        eq(owner),
                        anyString(),
                        isNull(),
                        eq(monday),
                        isNull(),
                        anyString(),
                        eq("monthly"),
                        isNull());
        UUID id = UUID.randomUUID();
        when(store.owned(owner, id))
                .thenReturn(
                        Optional.of(
                                new TodoRepository.Row(
                                        id,
                                        "月待办",
                                        null,
                                        LocalDate.of(2024, 2, 1),
                                        null,
                                        "monthly",
                                        null,
                                        0)));
        service.complete(
                owner,
                id,
                LocalDate.of(2024, 2, 29),
                new CalendarDtos.CompleteOccurrence(true, 0L));
        verify(store).setCompleted(id, LocalDate.of(2024, 2, 1), true);
        assertThatThrownBy(
                        () ->
                                service.complete(
                                        owner,
                                        id,
                                        LocalDate.of(2024, 2, 28),
                                        new CalendarDtos.CompleteOccurrence(true, 0L)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void notesReadWholeIsoWeekAndActualMonthEnd() {
        when(store.periodic(eq(owner), any(), any(), anyString(), anyInt())).thenReturn(List.of());
        var response = service.periodic(owner, LocalDate.of(2024, 2, 28), "Asia/Shanghai");
        assertThat(response.weekly()).isEmpty();
        assertThat(response.monthly()).isEmpty();
        verify(store)
                .periodic(
                        owner, LocalDate.of(2024, 2, 26), LocalDate.of(2024, 3, 3), "weekly", 201);
        verify(store)
                .periodic(
                        owner,
                        LocalDate.of(2024, 2, 29),
                        LocalDate.of(2024, 2, 29),
                        "monthly",
                        201);
    }

    private CalendarDtos.CreateTodo request(String recurrence, Integer weekday) {
        return new CalendarDtos.CreateTodo(
                "待办", null, monday, LocalTime.NOON, "Asia/Shanghai", recurrence, weekday);
    }
}
