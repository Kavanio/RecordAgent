package com.butvan.recordagent.service.calendar;

import com.butvan.recordagent.dto.calendar.CalendarDtos;
import com.butvan.recordagent.dto.query.Page;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** 待办发生日、周期完成和版本冲突服务接口。 */
public interface TodoService {

    /** 为当前身份创建待办；周待办落周日、月待办落月末，非法周期或时区返回 400。 */
    CalendarDtos.Created create(UUID owner, CalendarDtos.CreateTodo request);

    /** 按保存的自然日规则读取本人实例；完成状态批量取得，超限返回 422，不截断。 */
    CalendarDtos.Day day(UUID owner, LocalDate date, String zone);

    /** 读取完整月份实例计数和每天最多三条标题，不加载正文，不受明细上限影响。 */
    CalendarDtos.Month month(UUID owner, YearMonth month, String zone);

    /** 锁定本人待办后比较 expectedVersion；版本不符返回 409，越权或无此发生日返回 404。 */
    CalendarDtos.Occurrence complete(
            UUID owner, UUID id, LocalDate date, CalendarDtos.CompleteOccurrence request);

    /** 全月实例数与完成数，周期键以周一和月初归一化。 */
    record Progress(long total, long completed) {}

    /** 完整月统计，独立于明细上限。 */
    Progress progress(UUID owner, YearMonth month, String zone);

    /** 分页读取当日待办；周期和时区口径与 day 一致。 */
    Page<CalendarDtos.Todo> page(UUID owner, LocalDate date, String zone, int offset, int limit);
}
