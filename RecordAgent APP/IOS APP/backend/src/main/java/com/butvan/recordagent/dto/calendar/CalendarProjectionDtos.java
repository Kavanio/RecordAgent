package com.butvan.recordagent.dto.calendar;

import com.butvan.recordagent.dto.record.JournalDtos;
import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.butvan.recordagent.dto.study.StudyDtos;
import java.time.LocalDate;
import java.util.List;

/** 日历只读组合投影；事实仍由各业务服务持有。 */
public final class CalendarProjectionDtos {
    private CalendarProjectionDtos() {}

    public record Day(LocalDate date, List<CalendarDtos.Todo> todos,
            List<ScheduleDtos.Schedule> schedules, List<JournalDtos.Journal> journals,
            List<FinanceDtos.Transaction> transactions, List<StudyDtos.Session> studies) {}
    public record MonthDay(LocalDate date, int count, List<String> titles, List<String> kinds) {}
    public record Month(String month, List<MonthDay> days) {}
}
