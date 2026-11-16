package butvan.agent.network.mobiletask.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.mobiletask.dto.MobileTaskDtos;
import butvan.agent.network.mobiletask.dto.MobileTaskDtos.MobileTaskDeliveryResponse;
import butvan.agent.network.mobiletask.dto.MobileTaskDtos.MobileTaskResponse;
import butvan.agent.network.mobiletask.dto.MobileTaskDtos.PageResponse;
import butvan.agent.network.mobiletask.dto.MobileTaskDtos.RecordDeliveryRequest;
import butvan.agent.network.mobiletask.dto.MobileTaskDtos.SaveMobileTaskRequest;
import butvan.agent.network.mobiletask.service.MobileTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 手机端本地通知提醒任务 HTTP 协议适配层。 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/agent/mobile-tasks")
@RequiredArgsConstructor
public class MobileTaskController {

    private final MobileTaskService service;
    private final CurrentUserProvider currentUserProvider;

    @ApiLog("查询手机端提醒任务列表")
    @GetMapping
    public Result<PageResponse<MobileTaskResponse>> list(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(MobileTaskDtos.fromTasks(
                service.list(ownerId(), query, enabled, offset, limit)));
    }

    @ApiLog("查询已启用的手机端提醒任务")
    @GetMapping("/enabled")
    public Result<List<MobileTaskResponse>> enabled() {
        return Result.success(service.listEnabled(ownerId()).stream().map(MobileTaskDtos::from).toList());
    }

    @ApiLog("查询手机端提醒任务详情")
    @GetMapping("/{id}")
    public Result<MobileTaskResponse> get(@PathVariable String id) {
        return Result.success(MobileTaskDtos.from(service.get(ownerId(), id)));
    }

    @ApiLog("创建手机端提醒任务")
    @PostMapping
    public Result<MobileTaskResponse> create(@RequestBody SaveMobileTaskRequest request) {
        return Result.success(MobileTaskDtos.from(service.create(ownerId(), request)));
    }

    @ApiLog("更新手机端提醒任务")
    @PutMapping("/{id}")
    public Result<MobileTaskResponse> update(
            @PathVariable String id, @RequestBody SaveMobileTaskRequest request) {
        return Result.success(MobileTaskDtos.from(service.update(ownerId(), id, request)));
    }

    @ApiLog("删除手机端提醒任务")
    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable String id, @RequestParam long version) {
        service.delete(ownerId(), id, version);
        return Result.success("手机提醒任务已删除");
    }

    @ApiLog("查询手机通知送达观察记录")
    @GetMapping("/{id}/deliveries")
    public Result<PageResponse<MobileTaskDeliveryResponse>> deliveries(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.success(MobileTaskDtos.fromDeliveries(
                service.deliveries(ownerId(), id, offset, limit)));
    }

    @ApiLog("记录手机通知送达观察回执")
    @PostMapping("/{id}/deliveries")
    public Result<String> recordDelivery(
            @PathVariable String id, @RequestBody RecordDeliveryRequest request) {
        service.recordDelivery(ownerId(), id, request.eventId(), request.deliveredAt());
        return Result.success("已记录送达回执");
    }

    private String ownerId() {
        return currentUserProvider.currentUserId();
    }
}
