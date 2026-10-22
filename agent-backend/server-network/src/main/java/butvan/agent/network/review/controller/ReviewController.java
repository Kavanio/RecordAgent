package butvan.agent.network.review.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.review.dto.ReviewDtos.*;
import butvan.agent.network.review.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 长期复盘基础 HTTP Interface；owner 只来自当前用户，不接受请求体指定。 */
@RestController
@RequestMapping("/agent/reviews")
@RequiredArgsConstructor
public class ReviewController {
    private final CurrentUserProvider user;
    private final ReviewAuthorizationService authorization;
    private final ReviewFactService facts;
    private final ReviewGoalService goals;
    private final ReviewDataService data;

    @ApiLog("查询长期复盘设置") @GetMapping("/settings")
    public Result<SettingsView> settings() { return Result.success(authorization.settings(user.currentUserId())); }

    @ApiLog("保存长期复盘设置") @PutMapping("/settings")
    public Result<SettingsView> settings(@RequestBody SaveSettings input) {
        return Result.success(authorization.save(user.currentUserId(), input));
    }

    @ApiLog("明确授予长期复盘权限") @PostMapping("/grants")
    public Result<GrantView> grant(@RequestBody GrantRequest input) {
        return Result.success(authorization.grant(user.currentUserId(), input));
    }

    @ApiLog("撤销长期复盘权限") @PostMapping("/grants/{id}/revoke")
    public Result<GrantView> revoke(@PathVariable String id, @RequestBody RevokeRequest input) {
        return Result.success(authorization.revoke(user.currentUserId(), id, input));
    }

    @ApiLog("采集授权范围内的历史事实摘要") @PostMapping("/facts/capture")
    public Result<SnapshotView> capture(@RequestBody CaptureRequest input) {
        return Result.success(facts.capture(user.currentUserId(), input));
    }

    @ApiLog("查询复盘历史事实摘要") @GetMapping("/facts")
    public Result<List<SnapshotSummaryView>> facts(@RequestParam(defaultValue = "20") int limit) {
        return Result.success(facts.list(user.currentUserId(), limit));
    }

    @ApiLog("读取复盘事实摘要") @GetMapping("/facts/{id}")
    public Result<SnapshotView> fact(@PathVariable String id) { return Result.success(facts.get(user.currentUserId(), id)); }

    @ApiLog("清除复盘摘要和目标并撤销授权") @PostMapping("/erase")
    public Result<EraseResult> erase(@RequestBody EraseRequest input) {
        return Result.success(data.erase(user.currentUserId(), input));
    }

    @ApiLog("查询用户明确复盘目标") @GetMapping("/goals")
    public Result<List<GoalView>> goals() { return Result.success(goals.list(user.currentUserId())); }

    @ApiLog("创建用户明确复盘目标") @PostMapping("/goals")
    public Result<GoalView> createGoal(@RequestBody SaveGoal input) {
        return Result.success(goals.save(user.currentUserId(), null, input));
    }

    @ApiLog("修改或归档用户明确复盘目标") @PutMapping("/goals/{id}")
    public Result<GoalView> updateGoal(@PathVariable String id, @RequestBody SaveGoal input) {
        return Result.success(goals.save(user.currentUserId(), id, input));
    }
}
