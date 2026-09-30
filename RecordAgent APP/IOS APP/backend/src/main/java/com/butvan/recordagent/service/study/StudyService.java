package com.butvan.recordagent.service.study;

import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.study.StudyDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** 学习记录按自然日拆分后的公开只读边界。 */
public interface StudyService {
    /** 查询某日与该日重叠的已完成时段。 */
    List<StudyDtos.Session> day(UUID owner, LocalDate date, String timeZone);

    /** 查询目标月的自然日投影，跨日时段按日拆分。 */
    List<DailySummary> month(UUID owner, YearMonth month, String timeZone);

    /** 查询本人累计已完成分钟数。 */
    long totalMinutes(UUID owner);

    /** 指定时区某自然日的完整分钟聚合。 */
    long minutesOn(UUID owner, LocalDate date, String timeZone);

    /** 按请求时区分页读取当日已结束学习时段。 */
    Page<StudyDtos.Session> page(
            UUID owner, LocalDate date, String timeZone, int offset, int limit);

    /** 分页读取与指定自然月重叠的完整时段；活动时段通过 active 单独读取。 */
    Page<StudyDtos.Entry> entries(
            UUID owner, YearMonth month, String zone, String query, int offset, int limit);

    /** 完整月内分钟、时段数和有记录的自然日，不从分页列表推算。 */
    StudyDtos.Overview overview(UUID owner, YearMonth month, String zone);

    /** 开始计时或补录已结束时段；同 UUID 同数据可重试，跨日按同一事实投影。 */
    StudyDtos.Entry create(UUID owner, StudyDtos.Save draft);

    /** 当前活跃时段，支持重启恢复。 */
    StudyDtos.Entry active(UUID owner);

    /** 服务端确认结束后才计入统计；同结束时间重试是幂等的。 */
    StudyDtos.Entry finish(UUID owner, UUID id, StudyDtos.Finish draft);

    /** 删除或放弃计时，携带版本号并校验所有权。 */
    void delete(UUID owner, UUID id, long version);
}
