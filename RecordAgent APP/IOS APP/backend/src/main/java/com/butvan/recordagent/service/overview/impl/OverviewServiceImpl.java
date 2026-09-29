package com.butvan.recordagent.service.overview.impl;

import com.butvan.recordagent.dto.overview.OverviewDtos;
import com.butvan.recordagent.service.calendar.TodoService;
import com.butvan.recordagent.service.finance.FinanceService;
import com.butvan.recordagent.service.overview.OverviewService;
import com.butvan.recordagent.service.record.JournalService;
import com.butvan.recordagent.service.study.StudyService;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 仅依赖各领域公开服务组合概览，不在此处操作业务表。 */
@Service
@RequiredArgsConstructor
public class OverviewServiceImpl implements OverviewService {
    private final TodoService todos;
    private final FinanceService finance;
    private final JournalService journals;
    private final StudyService study;

    @Override
    @Transactional(readOnly = true)
    public OverviewDtos.DayTrend dayTrend(UUID owner, LocalDate date, String zone) {
        BusinessTimeZone.parse(zone);
        List<OverviewDtos.TrendDay> trend = new ArrayList<>(7);
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate day = date.minusDays(offset);
            long expense = finance.day(owner, day).stream().filter(row -> row.type().equals("expense"))
                    .mapToLong(row -> row.amountInCents()).sum();
            long minutes = study.day(owner, day, zone).stream().mapToLong(row -> row.durationMinutes()).sum();
            trend.add(new OverviewDtos.TrendDay(day, expense, minutes));
        }
        return new OverviewDtos.DayTrend(date, trend);
    }

    @Override
    @Transactional(readOnly = true)
    public OverviewDtos.Profile profile(UUID owner, YearMonth month, String zone) {
        BusinessTimeZone.parse(zone);
        var financeSummary = finance.summary(owner);
        var documentSummary = journals.summary(owner);
        long todoCount = 0;
        long completed = 0;
        for (int index = 1; index <= month.lengthOfMonth(); index++) {
            var day = todos.day(owner, month.atDay(index), zone);
            todoCount += day.todos().size();
            completed += day.todos().stream().filter(row -> row.completed()).count();
        }
        // 尚无历史余额快照，增长率返回 null，客户端不会显示虚构百分比。
        return new OverviewDtos.Profile(month.toString(), financeSummary.totalAssetsInCents(),
                financeSummary.totalExpenseInCents(), study.totalMinutes(owner),
                documentSummary.documentCount(), documentSummary.characterCount(),
                completed, todoCount, null, null, null);
    }
}
