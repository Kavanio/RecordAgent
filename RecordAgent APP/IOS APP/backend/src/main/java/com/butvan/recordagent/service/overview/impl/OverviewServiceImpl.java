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
            long expense = finance.expenseOn(owner, day);
            long minutes = study.minutesOn(owner, day, zone);
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
        var progress = todos.progress(owner, month, zone);
        // 尚无历史余额快照，增长率返回 null，客户端不会显示虚构百分比。
        return new OverviewDtos.Profile(month.toString(), financeSummary.totalAssetsInCents(),
                financeSummary.totalExpenseInCents(), study.totalMinutes(owner),
                documentSummary.documentCount(), documentSummary.characterCount(),
                progress.completed(), progress.total(), null, null, null);
    }
}
