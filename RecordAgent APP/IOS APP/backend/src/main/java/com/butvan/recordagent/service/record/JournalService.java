package com.butvan.recordagent.service.record;

import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.record.JournalDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** 手记是资料领域的唯一事实，日历只消费其投影。 */
public interface JournalService {
    record Summary(long documentCount, long characterCount) {}

    /** 为当前身份创建纯文本手记；日期沿用用户选择自然日，正文可为空，不写入日志。 */
    JournalDtos.Created create(UUID owner, JournalDtos.Create request);

    /** 读取本人当日未入回收站手记，超限返回 422；正文仅在明细接口加载。 */
    List<JournalDtos.Journal> day(UUID owner, LocalDate date);

    /** 返回全月完整计数及每天最多三条标题，不读取正文。 */
    List<DailySummary> month(UUID owner, YearMonth month);

    /** 读取本人资料累计条目和字数。 */
    Summary summary(UUID owner);

    /** 分页读取本人自然日明细，offset 从零开始，limit 最大为配置上限。 */
    Page<JournalDtos.Journal> page(UUID owner, LocalDate date, int offset, int limit);
}
