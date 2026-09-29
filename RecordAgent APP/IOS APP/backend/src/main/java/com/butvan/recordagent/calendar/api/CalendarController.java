package com.butvan.recordagent.calendar.api;

import com.butvan.recordagent.calendar.application.TodoService;
import com.butvan.recordagent.platform.observability.ApiLog;
import com.butvan.recordagent.platform.security.AuthenticatedUser;
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

    @ApiLog("创建待办")
    @PostMapping("/todos")
    public CalendarDtos.Created createTodo(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CalendarDtos.CreateTodo request) {
        return todos.create(user.userId(), request);
    }

    @ApiLog("读取日历当日待办")
    @GetMapping("/days/{date}")
    public CalendarDtos.Day day(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable LocalDate date, @RequestParam String timeZone) {
        return todos.day(user.userId(), date, timeZone);
    }

    @ApiLog("读取月历待办摘要")
    @GetMapping("/months/{month}")
    public CalendarDtos.Month month(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable YearMonth month, @RequestParam String timeZone) {
        return todos.month(user.userId(), month, timeZone);
    }

    @ApiLog("更新待办实例完成状态")
    @PatchMapping("/todos/{id}/occurrences/{date}")
    public CalendarDtos.Occurrence complete(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @PathVariable LocalDate date,
            @Valid @RequestBody CalendarDtos.CompleteOccurrence request) {
        return todos.complete(user.userId(), id, date, request);
    }
}
