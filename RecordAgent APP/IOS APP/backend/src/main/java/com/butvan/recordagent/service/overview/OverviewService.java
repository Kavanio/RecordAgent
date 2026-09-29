package com.butvan.recordagent.service.overview;

import com.butvan.recordagent.dto.overview.OverviewDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** 首页和个人页跨领域的只读组合边界。 */
public interface OverviewService {
    /** 读取截至选中自然日的七日趋势。 */
    OverviewDtos.DayTrend dayTrend(UUID owner, LocalDate date, String timeZone);
    /** 读取本人累计值和目标月待办完成率。 */
    OverviewDtos.Profile profile(UUID owner, YearMonth month, String timeZone);
}
