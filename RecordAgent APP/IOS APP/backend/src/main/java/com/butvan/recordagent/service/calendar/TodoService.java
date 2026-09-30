package com.butvan.recordagent.service.calendar;

import com.butvan.recordagent.dto.calendar.CalendarDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** 待办发生日、周期完成和版本冲突服务接口。 */
public interface TodoService {

    /** 创建待办。 */
    CalendarDtos.Created create(UUID owner, CalendarDtos.CreateTodo request);

    /** 读取当日待办。 */
    CalendarDtos.Day day(UUID owner, LocalDate date, String zone);

    /** 读取月历待办摘要。 */
    CalendarDtos.Month month(UUID owner, YearMonth month, String zone);

    /** 更新待办实例完成状态。 */
    CalendarDtos.Occurrence complete(UUID owner, UUID id, LocalDate date, CalendarDtos.CompleteOccurrence request);
    /** 全月实例数与完成数，周期键以周一和月初归一化。 */
    record Progress(long total, long completed) {}

    /** 完整月统计，独立于明细上限。 */
    Progress progress(UUID owner, YearMonth month, String zone);
    /** 分页读取当日待办；周期和时区口径与 day 一致。 */
    com.butvan.recordagent.dto.query.Page<CalendarDtos.Todo> page(UUID owner, LocalDate date, String zone, int offset, int limit);

}
