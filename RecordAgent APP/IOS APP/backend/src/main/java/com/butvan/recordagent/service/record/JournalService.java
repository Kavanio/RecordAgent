package com.butvan.recordagent.service.record;

import com.butvan.recordagent.dto.record.JournalDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** 手记是资料领域的唯一事实，日历只消费其投影。 */
public interface JournalService {
    record Summary(long documentCount, long characterCount) {}
    /** 创建一篇手记。 */
    JournalDtos.Created create(UUID owner, JournalDtos.Create request);
    /** 查询某日手记。 */
    List<JournalDtos.Journal> day(UUID owner, LocalDate date);
    /** 查询某月手记摘要。 */
    List<com.butvan.recordagent.service.query.DailySummary> month(UUID owner, YearMonth month);
    /** 读取本人资料累计条目和字数。 */
    Summary summary(UUID owner);
    /** 分页读取本人自然日明细，offset 从零开始，limit 最大为配置上限。 */
    com.butvan.recordagent.dto.query.Page<JournalDtos.Journal> page(UUID owner, LocalDate date, int offset, int limit);

}
