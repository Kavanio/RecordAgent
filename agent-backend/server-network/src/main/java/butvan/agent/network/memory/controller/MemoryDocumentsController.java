package butvan.agent.network.memory.controller;

import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.memory.dto.MemoryDtos.*;
import butvan.agent.network.memory.service.MemoryManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 来源与任务协议适配；所有权、版本和重置保护由应用层校验。 */
@RestController
@RequestMapping("/agent/memory")
@RequiredArgsConstructor
public class MemoryDocumentsController {
    private final MemoryManagementService service;

    /** 分页读取来源元数据，不读取历史聊天列表。 */
    @GetMapping("/documents") @ApiLog("分页读取经历记忆来源")
    public Result<DocumentPage> documents(@RequestParam(defaultValue = "0") String cursor, @RequestParam(defaultValue = "20") int limit) {
        return Result.success(service.documents(cursor, limit));
    }
    /** 按需查看当前用户的权威原文及本地纠正。 */
    @GetMapping("/documents/{id}") @ApiLog("读取经历记忆来源详情")
    public Result<DocumentDetail> document(@PathVariable String id) { return Result.success(service.document(id)); }
    /** 明确确认纠正，不扩大原保存授权。 */
    @PostMapping("/documents/{id}/corrections") @ApiLog("纠正经历记忆并停用旧来源")
    public Result<OperationResponse> correct(@PathVariable String id, @RequestBody Correct input) { return Result.success(service.correct(id, input)); }
    /** JSON 请求体承载版本与幂等标识。 */
    @DeleteMapping("/documents/{id}") @ApiLog("撤回经历记忆来源")
    public Result<OperationResponse> delete(@PathVariable String id, @RequestBody DeleteDocument input) { return Result.success(service.delete(id, input)); }
    /** 只读恢复任务状态，不发送外部请求。 */
    @GetMapping("/operations/{id}") @ApiLog("读取记忆纠正删除任务")
    public Result<OperationResponse> operation(@PathVariable String id) { return Result.success(service.operation(id)); }
    /** 显式继续原删除目标的核对，不重放保存。 */
    @PostMapping("/operations/{id}/retry") @ApiLog("继续核对记忆远端删除")
    public Result<OperationResponse> retry(@PathVariable String id) { return Result.success(service.retry(id)); }
}
