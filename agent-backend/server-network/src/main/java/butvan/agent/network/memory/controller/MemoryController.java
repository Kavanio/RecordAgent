package butvan.agent.network.memory.controller;

import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.memory.dto.MemoryDtos.*;
import butvan.agent.network.memory.model.MemoryModels;
import butvan.agent.network.memory.model.MemoryModels.Authorization;
import butvan.agent.network.memory.service.MemoryService;
import butvan.agent.network.memory.service.MemoryStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 经历记忆授权入口；后端解析身份，不接受任意接收地址或凭据。 */
@RestController
@RequestMapping("/agent/memory")
@RequiredArgsConstructor
public class MemoryController {
    private final MemoryService service;
    private final MemoryStatusService status;

    /** 返回同一权威快照中的开关、有效授权、运行保护与受信接收方。 */
    @GetMapping("/settings") @ApiLog("读取经历记忆设置与授权状态")
    public Result<SettingsResponse> settings() {
        var state = service.state();
        var settings = state.settings();
        var grant = state.grant();
        var runtime = service.runtimePolicy();
        return Result.success(new SettingsResponse(settings.version(), settings.autoSave(), settings.autoRecall(),
                grant.revision(), state.authorized(), !state.recipientFingerprint().isEmpty(), state.recipientFingerprint(),
                grant.save(), grant.recall(), grant.channels(), grant.effectiveAt(), grant.retentionDays(),
                grant.writeLimit(), grant.queryLimit(), grant.policyVersion(), grant.recallMode().name(),
                new RuntimePolicyResponse(runtime.version(), runtime.batchSize(), runtime.dailyWriteLimit(),
                        runtime.dailyStatusLimit(), runtime.dailyRecallLimit())));
    }
    /** 设置仅变更开关，不创建或扩大授权。 */
    @PutMapping("/settings") @ApiLog("保存经历记忆独立开关")
    public Result<SettingsResponse> settings(@RequestBody SaveSettings input) {
        service.saveSettings(input.expectedVersion(), input.autoSave(), input.autoRecall());
        return settings();
    }
    /** 运行保护只调节流，不改变用途、渠道、召回范围或已登记来源资格。 */
    @PutMapping("/runtime-policy") @ApiLog("保存经历记忆运行保护设置")
    public Result<RuntimePolicyResponse> runtimePolicy(@RequestBody SaveRuntimePolicy input) {
        var updated = service.saveRuntimePolicy(input.expectedVersion(), input.batchSize(), input.dailyWriteLimit(),
                input.dailyStatusLimit(), input.dailyRecallLimit());
        return Result.success(new RuntimePolicyResponse(updated.version(), updated.batchSize(), updated.dailyWriteLimit(),
                updated.dailyStatusLimit(), updated.dailyRecallLimit()));
    }
    /** 只读积压与暂停原因；不读取正文，也不探测远端服务。 */
    @GetMapping("/status") @ApiLog("读取经历记忆积压与运行状态")
    public Result<StatusResponse> status() { return Result.success(status.status()); }
    /** 接收用户明确确认，身份与生效时间由应用入口确定。 */
    @PostMapping("/authorization") @ApiLog("明确授权经历记忆处理范围")
    public Result<AuthorizationResponse> authorize(@RequestBody Authorize input) {
        var result = service.authorize(new Authorization(input.expectedRevision(), input.recipientFingerprint(),
                input.allowSave(), input.allowRecall(), input.channels(), value(input.retentionDays()),
                value(input.dailyWriteLimit()), value(input.dailyQueryLimit()), value(input.policyVersion()),
                recallMode(input.recallMode()), Boolean.TRUE.equals(input.continuousProcessing()),
                Boolean.TRUE.equals(input.acknowledgeSourceImpact()), input.confirmed(), input.requestId()));
        return Result.success(new AuthorizationResponse(result.revision(), result.effectiveAt()));
    }
    /** 幂等撤权，不承诺同步删除已经处理的远端数据。 */
    @DeleteMapping("/authorization") @ApiLog("撤销经历记忆处理授权")
    public Result<AuthorizationResponse> revoke(@RequestBody Revoke input) {
        var result = service.revoke(input.expectedRevision(), input.requestId());
        return Result.success(new AuthorizationResponse(result.revision(), result.effectiveAt()));
    }

    /** 缺省字段按 0 传入，由授权版本校验决定是否合法，不在协议层猜测扩权含义。 */
    private static int value(Integer input) { return input == null ? 0 : input; }
    /** 只接受固定枚举；非法值在应用入口按参数错误拒绝，不回退到长期模式。 */
    private static MemoryModels.RecallMode recallMode(String input) {
        if (input == null || input.isBlank()) return MemoryModels.RecallMode.RECENT_DAYS;
        return MemoryModels.RecallMode.valueOf(input);
    }
}
