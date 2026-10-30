package butvan.agent.network.review.life;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 生活复盘协议边界：用户只来自可信身份，写入受全局重置准入保护。 */
@RestController
@RequestMapping("/agent/life-reviews")
@RequiredArgsConstructor
public class LifeReviewController {
    private final CurrentUserProvider user;
    private final LifeAuthorizationService authorization;
    private final LifeReviewService reviews;
    private final LifeDeliveryService delivery;
    private final LifeOutreachService outreach;
    private final LifeReplyService replies;

    @ApiLog("读取生活复盘独立设置") @GetMapping("/settings")
    public Result<SettingsView> settings() { return Result.success(authorization.view(user.currentUserId())); }
    @ApiLog("保存生活复盘设置并使旧授权失效") @PutMapping("/settings")
    public Result<SettingsView> save(@RequestBody SaveSettings input) { return Result.success(authorization.save(user.currentUserId(), input)); }
    @ApiLog("明确授予生活复盘与渠道范围权限") @PostMapping("/grant")
    public Result<SettingsView> grant(@RequestBody Consent input) { return Result.success(authorization.grant(user.currentUserId(), input)); }
    @ApiLog("撤销生活复盘和主动呈现权限") @PostMapping("/revoke")
    public Result<SettingsView> revoke(@RequestBody Consent input) { return Result.success(authorization.revoke(user.currentUserId(), input)); }
    @ApiLog("准备有截止点的生活复盘版本") @PostMapping("/prepare")
    public Result<ContentView> prepare(@RequestBody Prepare input) { return Result.success(reviews.prepare(user.currentUserId(), input)); }
    @ApiLog("查询生活复盘历史版本") @GetMapping
    public Result<List<ContentView>> history() { return Result.success(reviews.history(user.currentUserId())); }
    @ApiLog("读取生活复盘既有版本不重新生成") @GetMapping("/{id}")
    public Result<ContentView> get(@PathVariable String id) { return Result.success(reviews.get(user.currentUserId(), id)); }
    @ApiLog("查询重置保留的只读外发副本") @GetMapping("/archives")
    public Result<List<String>> archives() { return Result.success(reviews.archives(user.currentUserId())); }
    @ApiLog("发送当前授权的冻结生活复盘副本") @PostMapping("/{id}/send")
    public Result<ContentView> send(@PathVariable String id) {
        delivery.send(user.currentUserId(), id, false); return Result.success(reviews.get(user.currentUserId(), id));
    }
    @ApiLog("明确重试失败复盘副本且不重新生成") @PostMapping("/{id}/retry")
    public Result<ContentView> retry(@PathVariable String id, @RequestBody Retry input) {
        if (input == null || !input.confirmed()) throw new IllegalArgumentException("请确认重试原副本");
        delivery.send(user.currentUserId(), id, true); return Result.success(reviews.get(user.currentUserId(), id));
    }
    @ApiLog("向同一生活复盘主题补充并创建新版本") @PostMapping("/{id}/reply")
    public Result<String> reply(@PathVariable String id, @RequestBody Reply input) {
        if (input == null) throw new IllegalArgumentException("补充内容不能为空");
        return Result.success(replies.reply(user.currentUserId(), id, input.requestId(), input.text()));
    }
    @ApiLog("查询主动候选和时机") @GetMapping("/candidates")
    public Result<List<CandidateView>> candidates() { return Result.success(outreach.candidates(user.currentUserId())); }
    @ApiLog("从已授权证据建立人工事件或恢复候选") @PostMapping("/candidates")
    public Result<List<CandidateView>> event(@RequestBody EventCandidate input) {
        outreach.event(user.currentUserId(), input); return Result.success(outreach.candidates(user.currentUserId()));
    }
    @ApiLog("手动建立有来源的主动候选") @PostMapping("/{id}/propose")
    public Result<List<CandidateView>> propose(@PathVariable String id) {
        outreach.propose(user.currentUserId(), id); return Result.success(outreach.candidates(user.currentUserId()));
    }
    @ApiLog("提交主动候选反馈或取消") @PostMapping("/candidates/{id}/feedback")
    public Result<List<CandidateView>> feedback(@PathVariable String id, @RequestBody Feedback input) {
        outreach.feedback(user.currentUserId(), id, input); return Result.success(outreach.candidates(user.currentUserId()));
    }
    @ApiLog("读取可纠正作息候选") @GetMapping("/habit")
    public Result<HabitView> habit() { return Result.success(outreach.habit(user.currentUserId())); }
    @ApiLog("明确纠正或停用作息候选") @PutMapping("/habit")
    public Result<HabitView> habit(@RequestBody SaveHabit input) { return Result.success(outreach.habit(user.currentUserId(), input)); }
}
