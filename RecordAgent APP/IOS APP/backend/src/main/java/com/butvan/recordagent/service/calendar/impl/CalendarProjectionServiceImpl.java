package com.butvan.recordagent.service.calendar.impl;

import com.butvan.recordagent.dto.calendar.CalendarProjectionDtos;
import com.butvan.recordagent.service.calendar.CalendarProjectionService;
import com.butvan.recordagent.service.calendar.ScheduleService;
import com.butvan.recordagent.service.calendar.TodoService;
import com.butvan.recordagent.service.finance.FinanceService;
import com.butvan.recordagent.service.record.JournalService;
import com.butvan.recordagent.service.study.StudyService;
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
        return new CalendarProjectionDtos.Day(date, todos.day(owner, date, zone).todos(),
                schedules.day(owner, date), journals.day(owner, date), finance.day(owner, date),
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
        for (var schedule : schedules.month(owner, month)) {
            DaySummary summary = days.computeIfAbsent(schedule.date(), ignored -> new DaySummary());
            summary.count++;
            summary.kinds.add("schedule");
            summary.addTitle(schedule.title());
        }
        for (var journal : journals.month(owner, month)) {
            DaySummary summary = days.computeIfAbsent(journal.date(), ignored -> new DaySummary());
            summary.count++;
            summary.kinds.add("journal");
            summary.addTitle(journal.title());
        }
        for (var transaction : finance.month(owner, month)) {
            DaySummary summary = days.computeIfAbsent(transaction.date(), ignored -> new DaySummary());
            summary.count++;
            summary.kinds.add(transaction.type());
            summary.addTitle(transaction.category() == null || transaction.category().isBlank()
                    ? transaction.type() : transaction.category());
        }
        for (var session : study.month(owner, month, zone)) {
            DaySummary summary = days.computeIfAbsent(session.date(), ignored -> new DaySummary());
            summary.count++;
            summary.kinds.add("focus");
            summary.addTitle(session.title());
        }
        List<CalendarProjectionDtos.MonthDay> result = days.entrySet().stream().map(item ->
                new CalendarProjectionDtos.MonthDay(item.getKey(), item.getValue().count,
                        List.copyOf(item.getValue().titles), List.copyOf(item.getValue().kinds))).toList();
        return new CalendarProjectionDtos.Month(month.toString(), result);
    }

    private static final class DaySummary {
        private int count;
        private final List<String> titles = new ArrayList<>(3);
        private final LinkedHashSet<String> kinds = new LinkedHashSet<>();

        private void addTitle(String title) {
            if (title != null && !title.isBlank() && titles.size() < 3) titles.add(title);
        }
    }
}
