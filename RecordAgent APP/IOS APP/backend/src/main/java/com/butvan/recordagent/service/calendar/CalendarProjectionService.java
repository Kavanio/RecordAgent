package com.butvan.recordagent.service.calendar;

import com.butvan.recordagent.dto.calendar.CalendarProjectionDtos;
import com.butvan.recordagent.dto.query.Page;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** 组合各领域的有界日历读取。 */
public interface CalendarProjectionService {
    /** 读取单日完整投影。 */
    CalendarProjectionDtos.Day day(UUID owner, LocalDate date, String timeZone);

    /** 读取月历分类和标题摘要。 */
    CalendarProjectionDtos.Month month(UUID owner, YearMonth month, String timeZone);

    /** 按领域分页读取，kind 为 todo/schedule/journal/finance/study。 */
    Page<?> page(UUID owner, LocalDate date, String kind, String zone, int offset, int limit);
}
