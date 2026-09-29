package com.butvan.recordagent.service.todo.impl;

import com.butvan.recordagent.dto.todo.CalendarDtos;
import com.butvan.recordagent.repository.todo.TodoStore;
import com.butvan.recordagent.service.todo.TodoService;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 待办发生日、周期完成和版本冲突规则实现。 */
@Service
@RequiredArgsConstructor
public class TodoServiceImpl implements TodoService {
    private final TodoStore store;

    @Override
    @Transactional
    public CalendarDtos.Created create(UUID owner, CalendarDtos.CreateTodo request) {
        validateZone(request.timeZone());
        if (!List.of("none", "daily", "weekly", "monthly").contains(request.recurrence())) {
            throw error(ApiErrorCode.INVALID_ARGUMENT);
        }
        UUID id = UUID.randomUUID();
        store.create(id, owner, request.title().strip(), request.detail(), request.date(), request.time(), request.timeZone(),
                request.recurrence());
        return new CalendarDtos.Created(id, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarDtos.Day day(UUID owner, LocalDate date, String zone) {
        validateZone(zone);
        List<CalendarDtos.Todo> todos = new ArrayList<>();
        for (var row : store.candidates(owner, date)) {
            if (occurs(row, date)) {
                todos.add(new CalendarDtos.Todo(row.id(), row.title(), row.detail(), date, row.time(), row.recurrence(),
                        store.completed(row.id(), period(row, date)), row.version()));
            }
        }
        return new CalendarDtos.Day(date, todos);
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarDtos.Month month(UUID owner, YearMonth month, String zone) {
        validateZone(zone);
        List<CalendarDtos.MonthDay> days = new ArrayList<>();
        var candidates = store.candidates(owner, month.atEndOfMonth());
        for (int index = 1; index <= month.lengthOfMonth(); index++) {
            LocalDate date = month.atDay(index);
            List<String> titles = candidates.stream().filter(row -> occurs(row, date))
                    .limit(3).map(TodoStore.Row::title).toList();
            int count = (int) candidates.stream().filter(row -> occurs(row, date)).count();
            if (count > 0) days.add(new CalendarDtos.MonthDay(date, count, titles));
        }
        return new CalendarDtos.Month(month.toString(), days);
    }

    @Override
    @Transactional
    public CalendarDtos.Occurrence complete(UUID owner, UUID id, LocalDate date,
            CalendarDtos.CompleteOccurrence request) {
        var row = store.owned(owner, id).orElseThrow(() -> error(ApiErrorCode.NOT_FOUND));
        if (!occurs(row, date)) throw error(ApiErrorCode.NOT_FOUND);
        if (row.version() != request.expectedVersion()) throw error(ApiErrorCode.VERSION_CONFLICT);
        store.setCompleted(id, period(row, date), request.completed());
        store.incrementVersion(id);
        return new CalendarDtos.Occurrence(id, date, request.completed(), row.version() + 1);
    }

    private static boolean occurs(TodoStore.Row row, LocalDate date) {
        if (date.isBefore(row.date())) return false;
        return switch (row.recurrence()) {
            case "none" -> date.equals(row.date());
            case "daily" -> true;
            case "weekly" -> date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            case "monthly" -> date.equals(date.with(TemporalAdjusters.lastDayOfMonth()))
                    && !date.withDayOfMonth(1).isBefore(row.date().withDayOfMonth(1));
            default -> false;
        };
    }

    private static LocalDate period(TodoStore.Row row, LocalDate date) {
        return switch (row.recurrence()) {
            case "weekly" -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
            case "monthly" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private static void validateZone(String zone) {
        try { ZoneId.of(zone); } catch (Exception exception) { throw error(ApiErrorCode.INVALID_ARGUMENT); }
    }
    private static ApiException error(ApiErrorCode code) { return new ApiException(code, code.name()); }
}
