package butvan.agent.network.insight.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.insight.dto.DailyInsightDtos;
import butvan.agent.network.insight.dto.DailyInsightDtos.DailyInsightResponse;
import butvan.agent.network.insight.dto.DailyInsightDtos.ProfileOverviewResponse;
import butvan.agent.network.insight.service.DailyInsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** 跨领域每日洞察的 HTTP 适配层。 */
@RestController
@RequestMapping("/agent/insights")
@RequiredArgsConstructor
public class DailyInsightController {
    private final DailyInsightService dailyInsightService;
    private final CurrentUserProvider currentUserProvider;

    /** 查询指定自然日的确定性活动汇总。 */
    @ApiLog("查询每日活动洞察")
    @GetMapping("/daily")
    public Result<DailyInsightResponse> daily(
            @RequestParam LocalDate date,
            @RequestParam String timezone) {
        return Result.success(DailyInsightDtos.from(dailyInsightService.getDailyInsight(
                currentUserProvider.currentUserId(), date, parseTimezone(timezone))));
    }

    /** 查询指定自然月的个人统计总览（用于移动端“我的”页面）。 */
    @ApiLog("查询个人月度统计总览")
    @GetMapping("/profile")
    public Result<ProfileOverviewResponse> profile(
            @RequestParam String month,
            @RequestParam(defaultValue = "Asia/Shanghai") String timezone) {
        return Result.success(DailyInsightDtos.from(dailyInsightService.getProfileOverview(
                currentUserProvider.currentUserId(), parseMonth(month), parseTimezone(timezone))));
    }

    private YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("统计月份格式应为 YYYY-MM");
        }
    }

    private ZoneId parseTimezone(String value) {
        try {
            return value == null || value.isBlank() ? ZoneId.systemDefault() : ZoneId.of(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("每日洞察时区不合法");
        }
    }
}
