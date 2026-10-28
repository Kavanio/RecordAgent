package butvan.agent.network.reset.controller;

import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.reset.dto.ResetDtos.*;
import butvan.agent.network.reset.service.AgentResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 仅设置页可用的明确确认入口，不注册为模型工具。 */
@RestController
@RequestMapping("/agent/reset")
@RequiredArgsConstructor
public class AgentResetController {
    private final AgentResetService service;
    @PostMapping("/preview") @ApiLog("预览 Agent 恢复出厂范围")
    public Result<Preview> preview() { return Result.success(service.preview()); }
    @PostMapping @ApiLog("确认 Agent 恢复出厂设置")
    public Result<Status> confirm(@RequestBody Confirm input) { return Result.success(service.confirm(input)); }
    @GetMapping("/current") @ApiLog("查询当前 Agent 重置任务")
    public Result<Status> current() { return Result.success(service.current()); }
    @GetMapping("/{resetId}") @ApiLog("查询 Agent 重置结果")
    public Result<Status> get(@PathVariable String resetId) { return Result.success(service.get(resetId)); }
    @PostMapping("/{resetId}/retry") @ApiLog("重试未完成的 Agent 重置")
    public Result<Status> retry(@PathVariable String resetId, @RequestBody Retry input) {
        return Result.success(service.retry(resetId, input));
    }
}
