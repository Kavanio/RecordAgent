package com.butvan.recordagent.service.calendar;

import com.butvan.recordagent.dto.calendar.ScheduleDtos;
import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** 日程事实的公开业务边界。 */
public interface ScheduleService {
    /** 按 IANA 时区将时间点转为本人日程自然日；当前只支持同日结束，结束不晚于开始返回 400。 */
    ScheduleDtos.Created create(UUID owner, ScheduleDtos.Create request);

    /** 读取本人已确认日程；日期是创建时区下的保存日期，超限返回 422。 */
    List<ScheduleDtos.Schedule> day(UUID owner, LocalDate date);

    /** 全月按日聚合完整计数和最多三条标题，不读取日程详情。 */
    List<DailySummary> month(UUID owner, YearMonth month);

    /** 分页读取本人自然日明细，offset 从零开始，limit 最大为配置上限。 */
    Page<ScheduleDtos.Schedule> page(UUID owner, LocalDate date, int offset, int limit);
}
