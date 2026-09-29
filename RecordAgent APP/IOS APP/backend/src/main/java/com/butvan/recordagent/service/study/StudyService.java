package com.butvan.recordagent.service.study;

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
    List<StudyDtos.Session> month(UUID owner, YearMonth month, String timeZone);
    /** 查询本人累计已完成分钟数。 */
    long totalMinutes(UUID owner);
}
