package com.butvan.recordagent.controller.task;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.task.TaskDtos;
import com.butvan.recordagent.security.AuthenticatedUser;
import com.butvan.recordagent.service.task.TaskService;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 受认证的手机任务配置与观察历史；不执行桌面自动任务。 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskService tasks;

    @ApiLog("分页读取本人手机任务")
    @GetMapping
    public Page<TaskDtos.Task> list(
            @AuthenticationPrincipal AuthenticatedUser u,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return tasks.page(u.userId(), query, enabled, offset, limit);
    }

    @ApiLog("读取本人手机任务详情")
    @GetMapping("/{id}")
    public TaskDtos.Task detail(
            @AuthenticationPrincipal AuthenticatedUser u, @PathVariable UUID id) {
        return tasks.detail(u.userId(), id);
    }

    @ApiLog("读取本人待注册手机提醒")
    @GetMapping("/enabled")
    public List<TaskDtos.Task> enabled(@AuthenticationPrincipal AuthenticatedUser u) {
        return tasks.enabled(u.userId());
    }

    @ApiLog("创建手机自动提醒")
    @PostMapping
    public TaskDtos.Task create(
            @AuthenticationPrincipal AuthenticatedUser u, @Valid @RequestBody TaskDtos.Save s) {
        return tasks.create(u.userId(), s);
    }

    @ApiLog("修改或启停手机自动提醒")
    @PutMapping("/{id}")
    public TaskDtos.Task update(
            @AuthenticationPrincipal AuthenticatedUser u,
            @PathVariable UUID id,
            @Valid @RequestBody TaskDtos.Save s) {
        return tasks.update(u.userId(), id, s);
    }

    @ApiLog("删除手机自动提醒")
    @DeleteMapping("/{id}")
    public void delete(
            @AuthenticationPrincipal AuthenticatedUser u,
            @PathVariable UUID id,
            @RequestParam long version) {
        tasks.delete(u.userId(), id, version);
    }

    @ApiLog("记录设备观察到的通知送达")
    @PostMapping("/{id}/deliveries")
    public void observe(
            @AuthenticationPrincipal AuthenticatedUser u,
            @PathVariable UUID id,
            @Valid @RequestBody TaskDtos.Observation s) {
        tasks.observe(u.userId(), id, s);
    }

    @ApiLog("分页读取本人任务观察历史")
    @GetMapping("/{id}/deliveries")
    public Page<TaskDtos.Delivery> history(
            @AuthenticationPrincipal AuthenticatedUser u,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return tasks.history(u.userId(), id, offset, limit);
    }
}
