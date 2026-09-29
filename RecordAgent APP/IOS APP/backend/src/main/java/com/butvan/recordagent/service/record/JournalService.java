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
    List<JournalDtos.Journal> month(UUID owner, YearMonth month);
    /** 读取本人资料累计条目和字数。 */
    Summary summary(UUID owner);
}
