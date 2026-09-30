package com.butvan.recordagent.service.calendar.impl;

import com.butvan.recordagent.dto.calendar.CalendarProjectionDtos;
import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.service.calendar.CalendarProjectionService;
import com.butvan.recordagent.service.calendar.ScheduleService;
import com.butvan.recordagent.service.calendar.TodoService;
import com.butvan.recordagent.service.finance.FinanceService;
import com.butvan.recordagent.service.record.JournalService;
import com.butvan.recordagent.service.study.StudyService;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 调用领域服务组合日历展示数据，不直接读取其他领域表。 */
@Service
@RequiredArgsConstructor
public class CalendarProjectionServiceImpl implements CalendarProjectionService {
    private final TodoService todos;
    private final ScheduleService schedules;
    private final JournalService journals;
    private final FinanceService finance;
    private final StudyService study;

    @Override
    @Transactional(readOnly = true)
    public CalendarProjectionDtos.Day day(UUID owner, LocalDate date, String zone) {
        return new CalendarProjectionDtos.Day(
                date,
                todos.day(owner, date, zone).todos(),
                schedules.day(owner, date),
                journals.day(owner, date),
                finance.day(owner, date),
                study.day(owner, date, zone));
    }

    @Override
    @Transactional(readOnly = true)
    public CalendarProjectionDtos.Month month(UUID owner, YearMonth month, String zone) {
        Map<LocalDate, DaySummary> days = new TreeMap<>();
        for (var todo : todos.month(owner, month, zone).days()) {
            DaySummary summary = days.computeIfAbsent(todo.date(), ignored -> new DaySummary());
            summary.count += todo.count();
            summary.kinds.add("todo");
            todo.titles().forEach(summary::addTitle);
        }
        addSummaries(days, schedules.month(owner, month));
        addSummaries(days, journals.month(owner, month));
        addSummaries(days, finance.month(owner, month));
        addSummaries(days, study.month(owner, month, zone));
        List<CalendarProjectionDtos.MonthDay> result =
                days.entrySet().stream()
                        .map(
                                item ->
                                        new CalendarProjectionDtos.MonthDay(
                                                item.getKey(),
                                                item.getValue().count,
                                                List.copyOf(item.getValue().titles),
                                                List.copyOf(item.getValue().kinds)))
                        .toList();
        return new CalendarProjectionDtos.Month(month.toString(), result);
    }

    /** 各领域完整计数相加，标题仍受月历最多三条的展示契约限制。 */
    private static void addSummaries(Map<LocalDate, DaySummary> days, List<DailySummary> rows) {
        for (var row : rows) {
            DaySummary summary = days.computeIfAbsent(row.date(), ignored -> new DaySummary());
            summary.count = Math.addExact(summary.count, row.count());
            summary.kinds.add(row.kind());
            row.titles().forEach(summary::addTitle);
        }
    }

    private static final class DaySummary {
        private int count;
        private final List<String> titles = new ArrayList<>(3);
        private final LinkedHashSet<String> kinds = new LinkedHashSet<>();

        private void addTitle(String title) {
            if (title != null && !title.isBlank() && titles.size() < 3) titles.add(title);
        }
    }

    /** 固定领域路由；领域服务各自维护所有权、排序及数量约束。 */
    @Override
    @Transactional(readOnly = true)
    public Page<?> page(
            UUID owner, LocalDate date, String kind, String zone, int offset, int limit) {
        BusinessTimeZone.parse(zone);
        return switch (kind) {
            case "todo" -> todos.page(owner, date, zone, offset, limit);
            case "schedule" -> schedules.page(owner, date, offset, limit);
            case "journal" -> journals.page(owner, date, offset, limit);
            case "finance" -> finance.page(owner, date, offset, limit);
            case "study" -> study.page(owner, date, zone, offset, limit);
            default -> throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid kind");
        };
    }
}
