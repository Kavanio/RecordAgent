package butvan.agent.network.review.life;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.review.life.LifeCompanionModels.*;
import butvan.agent.network.review.life.LifeReviewDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired(required = false)
    private LifeCompanionService companion;

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
    @ApiLog("读取基于互动时间戳的作息建议") @GetMapping("/habit/suggestion")
    public Result<HabitSuggestionView> habitSuggestion() { return Result.success(outreach.habitSuggestion(user.currentUserId())); }
    @ApiLog("明确纠正或停用作息候选") @PutMapping("/habit")
    public Result<HabitView> habit(@RequestBody SaveHabit input) { return Result.success(outreach.habit(user.currentUserId(), input)); }
    @ApiLog("查询久未互动问候等主动短消息记录") @GetMapping("/nudges")
    public Result<List<NudgeView>> nudges() { return Result.success(outreach.nudges(user.currentUserId())); }
    @ApiLog("提交主动短消息反馈") @PostMapping("/nudges/{id}/feedback")
    public Result<List<NudgeView>> nudgeFeedback(@PathVariable String id, @RequestBody NudgeFeedback input) {
        outreach.nudgeFeedback(user.currentUserId(), id, input); return Result.success(outreach.nudges(user.currentUserId()));
    }
    @ApiLog("读取系统性认知与主动陪伴状态") @GetMapping("/companion")
    public Result<CompanionView> companion() {
        return Result.success(requireCompanion().view(user.currentUserId()));
    }
    @ApiLog("订阅主动陪伴状态与桌面通知实时流") @GetMapping(value = "/companion/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter companionStream() {
        return requireCompanion().subscribe(user.currentUserId());
    }
    @ApiLog("修改并保存系统性用户认知") @PutMapping("/companion/cognition")
    public Result<CompanionView> saveCognition(@RequestBody SaveCognitionRequest input) {
        return Result.success(requireCompanion().saveCognition(user.currentUserId(), input));
    }
    @ApiLog("一键开启或配置主动陪伴与认知学习") @PostMapping("/companion/enable")
    public Result<CompanionView> enableCompanion(@RequestBody EnableCompanionRequest input) {
        return Result.success(requireCompanion().enableCompanion(user.currentUserId(), input));
    }
    @ApiLog("立即触发全量资料系统性认知学习") @PostMapping("/companion/learn")
    public Result<CompanionView> learnCompanion() {
        return Result.success(requireCompanion().learnNow(user.currentUserId()));
    }
    @ApiLog("立即触发一次自然口语化主动问候并写入主对话") @PostMapping("/companion/reach-out")
    public Result<CompanionView> reachOutCompanion(@RequestBody(required = false) ReachOutRequest input) {
        return Result.success(requireCompanion().reachOutNow(
                user.currentUserId(), input == null ? null : input.reasonHint()));
    }
    @ApiLog("确认已弹出桌面主动问候通知") @PostMapping("/companion/desktop-notices/{id}/ack")
    public Result<Void> ackDesktopNotice(@PathVariable String id) {
        requireCompanion().acknowledgeDesktopNotice(user.currentUserId(), id);
        return Result.success(null);
    }

    private LifeCompanionService requireCompanion() {
        if (companion == null) throw new IllegalStateException("主动陪伴服务未启用");
        return companion;
    }
}
