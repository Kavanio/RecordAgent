package com.butvan.recordagent.service.calendar.impl;

import com.butvan.recordagent.dto.calendar.CalendarDtos;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.calendar.TodoRepository;
import com.butvan.recordagent.service.calendar.TodoService;
import com.butvan.recordagent.service.query.ReadBounds;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 待办发生日、周期完成和版本冲突规则实现。 */
@Service
@RequiredArgsConstructor
public class TodoServiceImpl implements TodoService {
    private final TodoRepository store;
    private final ReadBounds bounds;

    @Override
    @Transactional
    public CalendarDtos.Created create(UUID owner, CalendarDtos.CreateTodo request) {
        BusinessTimeZone.parse(request.timeZone());
        if (!List.of("none", "daily", "weekly", "monthly").contains(request.recurrence())) {
            throw error(ApiErrorCode.INVALID_ARGUMENT);
        }
        UUID id = UUID.randomUUID();
        store.create(
                id,
                owner,
                request.title().strip(),
                request.detail(),
                request.date(),
                request.time(),
                request.timeZone(),
                request.recurrence());
        return new CalendarDtos.Created(id, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarDtos.Day day(UUID owner, LocalDate date, String zone) {
        BusinessTimeZone.parse(zone);
        var rows =
                bounds.requireComplete(
                        store.day(owner, date, bounds.dayLimit() + 1), bounds.dayLimit());
        return new CalendarDtos.Day(date, rows);
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarDtos.Month month(UUID owner, YearMonth month, String zone) {
        BusinessTimeZone.parse(zone);
        var days =
                store.month(owner, month.atDay(1), month.atEndOfMonth()).stream()
                        .map(
                                row ->
                                        new CalendarDtos.MonthDay(
                                                row.date(), row.count(), row.titles()))
                        .toList();
        return new CalendarDtos.Month(month.toString(), days);
    }

    /** 复用月历相同的 SQL 周期规则，单次聚合全月数量与完成数量。 */
    @Override
    @Transactional(readOnly = true)
    public Progress progress(UUID owner, YearMonth month, String zone) {
        BusinessTimeZone.parse(zone);
        var rows = store.month(owner, month.atDay(1), month.atEndOfMonth());
        return new Progress(
                rows.stream().mapToLong(TodoRepository.MonthRow::count).sum(),
                rows.stream().mapToLong(TodoRepository.MonthRow::completed).sum());
    }

    @Override
    @Transactional
    public CalendarDtos.Occurrence complete(
            UUID owner, UUID id, LocalDate date, CalendarDtos.CompleteOccurrence request) {
        var row = store.owned(owner, id).orElseThrow(() -> error(ApiErrorCode.NOT_FOUND));
        if (!occurs(row, date)) {
            throw error(ApiErrorCode.NOT_FOUND);
        }
        if (row.version() != request.expectedVersion()) {
            throw error(ApiErrorCode.VERSION_CONFLICT);
        }
        store.setCompleted(id, period(row, date), request.completed());
        store.incrementVersion(id);
        return new CalendarDtos.Occurrence(id, date, request.completed(), row.version() + 1);
    }

    private static boolean occurs(TodoRepository.Row row, LocalDate date) {
        if (date.isBefore(row.date())) {
            return false;
        }
        // 周待办显示在周日；月待办显示在自然月最后一天，创建月份也可生效。
        return switch (row.recurrence()) {
            case "none" -> date.equals(row.date());
            case "daily" -> true;
            case "weekly" -> date.getDayOfWeek() == DayOfWeek.SUNDAY;
            case "monthly" ->
                    date.equals(date.with(TemporalAdjusters.lastDayOfMonth()))
                            && !date.withDayOfMonth(1).isBefore(row.date().withDayOfMonth(1));
            default -> false;
        };
    }

    private static LocalDate period(TodoRepository.Row row, LocalDate date) {
        // 完成记录用周一或每月首日作周期键，同一周期只保存一份状态。
        return switch (row.recurrence()) {
            case "weekly" -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
            case "monthly" -> date.withDayOfMonth(1);
            default -> date;
        };
    }

    private static ApiException error(ApiErrorCode code) {
        return new ApiException(code, code.name());
    }

    /** 当日实例分页，不对每条实例单独查询完成状态。 */
    @Override
    @Transactional(readOnly = true)
    public Page<CalendarDtos.Todo> page(
            UUID owner, LocalDate date, String zone, int offset, int limit) {
        BusinessTimeZone.parse(zone);
        bounds.validatePage(offset, limit);
        return bounds.page(store.day(owner, date, limit + 1, offset), offset, limit);
    }
}
