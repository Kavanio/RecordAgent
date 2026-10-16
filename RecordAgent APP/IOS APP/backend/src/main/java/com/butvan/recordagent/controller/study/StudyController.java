package com.butvan.recordagent.controller.study;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.study.StudyDtos;
import com.butvan.recordagent.security.AuthenticatedUser;
import com.butvan.recordagent.service.study.StudyService;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 学习工作台不复用日历待办；活跃计时与补录都持久化为学习时段。 */
@RestController
@RequestMapping("/api/v1/study")
@RequiredArgsConstructor
public class StudyController {
    private final StudyService study;

    @ApiLog("分页读取本人学习时段")
    @GetMapping
    public Page<StudyDtos.Entry> list(
            @AuthenticationPrincipal AuthenticatedUser u,
            @RequestParam YearMonth month,
            @RequestParam String timeZone,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return study.entries(u.userId(), month, timeZone, query, offset, limit);
    }

    @ApiLog("读取本人学习月统计")
    @GetMapping("/overview")
    public StudyDtos.Overview overview(
            @AuthenticationPrincipal AuthenticatedUser u,
            @RequestParam YearMonth month,
            @RequestParam String timeZone) {
        return study.overview(u.userId(), month, timeZone);
    }

    @ApiLog("恢复本人活跃计时")
    @GetMapping("/active")
    public StudyDtos.Entry active(@AuthenticationPrincipal AuthenticatedUser u) {
        return study.active(u.userId());
    }

    @ApiLog("开始或补录学习时段")
    @PostMapping
    public StudyDtos.Entry create(
            @AuthenticationPrincipal AuthenticatedUser u, @Valid @RequestBody StudyDtos.Save s) {
        return study.create(u.userId(), s);
    }

    @ApiLog("结束本人学习计时")
    @PatchMapping("/{id}/finish")
    public StudyDtos.Entry finish(
            @AuthenticationPrincipal AuthenticatedUser u,
            @PathVariable UUID id,
            @Valid @RequestBody StudyDtos.Finish s) {
        return study.finish(u.userId(), id, s);
    }

    @ApiLog("删除本人学习时段")
    @DeleteMapping("/{id}")
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser u,
            @PathVariable UUID id,
            @RequestParam long version) {
        study.delete(u.userId(), id, version);
    }
}
