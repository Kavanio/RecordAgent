package com.butvan.recordagent.service.calendar;

import com.butvan.recordagent.dto.calendar.ScheduleDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** 日程事实的公开业务边界。 */
public interface ScheduleService {
    /** 为当前用户创建日程。 */
    ScheduleDtos.Created create(UUID owner, ScheduleDtos.Create request);
    /** 查询自然日内的日程。 */
    List<ScheduleDtos.Schedule> day(UUID owner, LocalDate date);
    /** 查询月历有界标题摘要。 */
    List<com.butvan.recordagent.service.query.DailySummary> month(UUID owner, YearMonth month);
    /** 分页读取本人自然日明细，offset 从零开始，limit 最大为配置上限。 */
    com.butvan.recordagent.dto.query.Page<ScheduleDtos.Schedule> page(UUID owner, LocalDate date, int offset, int limit);

}
