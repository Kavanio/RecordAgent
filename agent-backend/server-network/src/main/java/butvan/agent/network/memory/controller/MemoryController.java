package butvan.agent.network.memory.controller;

import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.memory.dto.MemoryDtos.*;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import butvan.agent.network.memory.service.MemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 经历记忆基础管理入口；后端解析身份，尚不提供正文管理、聊天接线或远端探测。 */
@RestController
@RequestMapping("/agent/memory")
@RequiredArgsConstructor
public class MemoryController {
    private final MemoryService service;

    /** 返回同一权威快照中的开关、有效授权与受信接收方。 */
    @GetMapping("/settings") @ApiLog("读取经历记忆设置与授权状态")
    public Result<SettingsResponse> settings() {
        var state = service.state();
        var settings = state.settings();
        var grant = state.grant();
        return Result.success(new SettingsResponse(settings.version(), settings.autoSave(), settings.autoRecall(),
                grant.revision(), state.authorized(), !state.recipientFingerprint().isEmpty(), state.recipientFingerprint()));
    }
    /** 设置仅变更开关，不创建或扩大授权。 */
    @PutMapping("/settings") @ApiLog("保存经历记忆独立开关")
    public Result<SettingsResponse> settings(@RequestBody SaveSettings input) {
        service.saveSettings(input.expectedVersion(), input.autoSave(), input.autoRecall());
        return settings();
    }
    /** 接收用户明确确认，身份与生效时间由应用入口确定。 */
    @PostMapping("/authorization") @ApiLog("明确授权经历记忆处理范围")
    public Result<AuthorizationResponse> authorize(@RequestBody Authorize input) {
        var result = service.authorize(new Authorization(input.expectedRevision(), input.recipientFingerprint(),
                input.allowSave(), input.allowRecall(), input.channels(), input.retentionDays(), input.dailyWriteLimit(),
                input.dailyQueryLimit(), input.confirmed(), input.requestId()));
        return Result.success(new AuthorizationResponse(result.revision(), result.effectiveAt()));
    }
    /** 幂等撤权，不承诺同步删除已经处理的远端数据。 */
    @DeleteMapping("/authorization") @ApiLog("撤销经历记忆处理授权")
    public Result<AuthorizationResponse> revoke(@RequestBody Revoke input) {
        var result = service.revoke(input.expectedRevision(), input.requestId());
        return Result.success(new AuthorizationResponse(result.revision(), result.effectiveAt()));
    }
}
