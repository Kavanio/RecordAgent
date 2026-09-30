package com.butvan.recordagent.controller.overview;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.dto.overview.OverviewDtos;
import com.butvan.recordagent.security.AuthenticatedUser;
import com.butvan.recordagent.service.overview.OverviewService;
import java.time.LocalDate;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 首页与个人统计的受认证只读入口。 */
@RestController
@RequestMapping("/api/v1/overview")
@RequiredArgsConstructor
public class OverviewController {
    private final OverviewService overview;

    @ApiLog("读取七日收支和专注趋势")
    @GetMapping("/days/{date}")
    public OverviewDtos.DayTrend dayTrend(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable LocalDate date,
            @RequestParam String timeZone) {
        return overview.dayTrend(user.userId(), date, timeZone);
    }

    @ApiLog("读取个人累计统计")
    @GetMapping("/profile")
    public OverviewDtos.Profile profile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam YearMonth month,
            @RequestParam String timeZone) {
        return overview.profile(user.userId(), month, timeZone);
    }
}
