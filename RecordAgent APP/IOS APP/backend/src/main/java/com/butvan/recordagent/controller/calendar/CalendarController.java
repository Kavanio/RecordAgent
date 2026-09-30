package com.butvan.recordagent.controller.calendar;

import com.butvan.recordagent.dto.calendar.CalendarDtos;
import com.butvan.recordagent.dto.calendar.CalendarProjectionDtos;
import com.butvan.recordagent.dto.calendar.ScheduleDtos;

import com.butvan.recordagent.service.calendar.CalendarProjectionService;
import com.butvan.recordagent.service.calendar.ScheduleService;
import com.butvan.recordagent.service.calendar.TodoService;
import com.butvan.recordagent.service.record.JournalService;
import com.butvan.recordagent.dto.record.JournalDtos;
import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.security.AuthenticatedUser;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 日历待办与有界日/月投影的 HTTP 入口。 */
@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
public class CalendarController {
    private final TodoService todos;
    private final ScheduleService schedules;
    private final JournalService journals;
    private final CalendarProjectionService projection;

    @ApiLog("创建待办")
    @PostMapping("/todos")
    public CalendarDtos.Created createTodo(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CalendarDtos.CreateTodo request) {
        return todos.create(user.userId(), request);
    }

    @ApiLog("读取日历当日事项")
    @GetMapping("/days/{date}")
    public CalendarProjectionDtos.Day day(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable LocalDate date, @RequestParam String timeZone) {
        return projection.day(user.userId(), date, timeZone);
    }

    @ApiLog("读取月历事项摘要")
    @GetMapping("/months/{month}")
    public CalendarProjectionDtos.Month month(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable YearMonth month, @RequestParam String timeZone) {
        return projection.month(user.userId(), month, timeZone);
    }

    @ApiLog("创建日程")
    @PostMapping("/schedules")
    public ScheduleDtos.Created createSchedule(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ScheduleDtos.Create request) {
        return schedules.create(user.userId(), request);
    }

    @ApiLog("创建手记")
    @PostMapping("/journals")
    public JournalDtos.Created createJournal(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody JournalDtos.Create request) {
        return journals.create(user.userId(), request);
    }

    @ApiLog("更新待办实例完成状态")
    @PatchMapping("/todos/{id}/occurrences/{date}")
    public CalendarDtos.Occurrence complete(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @PathVariable LocalDate date,
            @Valid @RequestBody CalendarDtos.CompleteOccurrence request) {
        return todos.complete(user.userId(), id, date, request);
    }
    /** 分页读取指定领域的当日明细；kind 仅允许固定五类，不接受表名或 SQL。 */
    @ApiLog("分页读取日历当日事项")
    @GetMapping("/days/{date}/pages/{kind}")
    public com.butvan.recordagent.dto.query.Page<?> page(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable LocalDate date, @PathVariable String kind, @RequestParam String timeZone,
            @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return projection.page(user.userId(), date, kind, timeZone, offset, limit);
    }

}
