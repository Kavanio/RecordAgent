package butvan.agent.network.automation.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.automation.dto.AutomationDtos.*;
import butvan.agent.network.automation.dto.TaskMailDtos;
import butvan.agent.network.automation.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;

/** 本机自动任务 API；所有权由身份提供器决定，不接受调用者指定用户。 */
@RestController
@RequestMapping("/agent/automations")
@RequiredArgsConstructor
public class AutomationController {
    private final AutomationService tasks;
    private final AutomationStream stream;
    private final TaskMailService mail;
    private final CurrentUserProvider user;
    /** 获取任务和待确认通知。 */
    @ApiLog("查询自动任务") @GetMapping
    public Result<Snapshot> list() { return Result.success(tasks.snapshot(user.currentUserId())); }
    /** 创建任务。 */
    @ApiLog("创建自动任务") @PostMapping
    public Result<TaskView> create(@RequestBody SaveRequest input) { return Result.success(tasks.save(user.currentUserId(), null, input)); }
    /** 修改任务。 */
    @ApiLog("修改自动任务") @PutMapping("/{id}")
    public Result<TaskView> update(@PathVariable String id, @RequestBody SaveRequest input) { return Result.success(tasks.save(user.currentUserId(), id, input)); }
    /** 暂停或恢复任务。 */
    @ApiLog("切换自动任务状态") @PostMapping("/{id}/state")
    public Result<TaskView> state(@PathVariable String id, @RequestBody StateRequest input) { return Result.success(tasks.state(user.currentUserId(), id, input)); }
    /** 软删除任务。 */
    @ApiLog("删除自动任务") @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id, @RequestParam int version) { tasks.delete(user.currentUserId(), id, version); return Result.success(null); }
    /** 从回收站恢复任务，保持暂停。 */
    @ApiLog("恢复已删除自动任务") @PostMapping("/{id}/restore")
    public Result<TaskView> restore(@PathVariable String id, @RequestParam int version) { return Result.success(tasks.restore(user.currentUserId(), id, version)); }
    /** 清空当前用户的任务回收站。 */
    @ApiLog("清空已删除自动任务") @DeleteMapping("/deleted/purge")
    public Result<Integer> purgeDeleted() { return Result.success(tasks.purgeDeleted(user.currentUserId())); }
    /** 预览不产生外部效果。 */
    @ApiLog("预览自动任务") @PostMapping("/preview")
    public Result<Preview> preview(@RequestBody Spec input) { return Result.success(tasks.preview(user.currentUserId(), input)); }
    /** 用户主动立即执行。 */
    @ApiLog("立即执行自动任务") @PostMapping("/{id}/run")
    public Result<RunView> run(@PathVariable String id) { return Result.success(tasks.runNow(user.currentUserId(), id)); }
    /** 最近执行历史。 */
    @ApiLog("查询任务历史") @GetMapping("/{id}/runs")
    public Result<List<RunView>> history(@PathVariable String id) { return Result.success(tasks.history(user.currentUserId(), id)); }
    /** 原子领取桌面投递。 */
    @ApiLog("领取任务桌面通知") @PostMapping("/runs/{id}/claim")
    public Result<Boolean> claim(@PathVariable String id) { return Result.success(tasks.claimDesktop(user.currentUserId(), id)); }
    /** 桌面实际提交回执。 */
    @ApiLog("保存任务通知回执") @PostMapping("/runs/{id}/receipt")
    public Result<Void> receipt(@PathVariable String id, @RequestBody DesktopReceipt input) { tasks.desktopReceipt(user.currentUserId(), id, input.status()); return Result.success(null); }
    /** 人工确认提醒。 */
    @ApiLog("确认任务提醒") @PostMapping("/runs/{id}/confirm")
    public Result<Void> confirm(@PathVariable String id) { tasks.confirm(user.currentUserId(), id); return Result.success(null); }
    /** 用户显式重发原邮件；未知结果必须单独确认重复风险。 */
    @ApiLog("重发任务邮件") @PostMapping("/runs/{id}/retry-email")
    public Result<Void> retryEmail(@PathVariable String id, @RequestBody RetryEmailRequest input) {
        tasks.retryMail(user.currentUserId(), id, input.allowUnknown()); return Result.success(null);
    }
    /** 原生采样不携带屏幕或按键内容。 */
    @ApiLog("更新电脑使用状态") @PostMapping("/activity")
    public Result<Void> activity(@RequestBody ActivitySample input) { tasks.activity(user.currentUserId(), input); return Result.success(null); }
    /** 多窗口实时恢复。 */
    @ApiLog("订阅自动任务状态") @GetMapping(value="/stream", produces="text/event-stream")
    public SseEmitter stream() { return stream.subscribe(user.currentUserId()); }
    /** 邮件设置查询脱敏。 */
    @ApiLog("查询任务邮件设置") @GetMapping("/mail/settings")
    public Result<TaskMailDtos.Settings> settings() { return Result.success(mail.settings()); }
    /** 不发送的邮件设置保存。 */
    @ApiLog("保存任务邮件设置") @PutMapping("/mail/settings")
    public Result<TaskMailDtos.Settings> settings(@RequestBody TaskMailDtos.Save input) { return Result.success(mail.save(input)); }
    /** 仅由用户显式点击触发测试邮件。 */
    @ApiLog("发送任务测试邮件") @PostMapping("/mail/test")
    public Result<TaskMailService.Outcome> testMail() { return Result.success(mail.test()); }
}
