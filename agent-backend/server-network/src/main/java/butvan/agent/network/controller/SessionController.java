package butvan.agent.network.controller;

import butvan.agent.agents.session.SessionLifecycleService;
import butvan.agent.agents.session.dto.CreateSessionRequest;
import butvan.agent.agents.session.dto.SessionDetailDto;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.UpdateSessionRequest;
import butvan.agent.agents.session.dto.PermissionModeRequest;
import butvan.agent.agents.session.dto.PermissionModeResponse;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 对外提供应用会话目录册与消息投影 API。 */
@RestController
@RequestMapping("/agent/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionLifecycleService sessionLifecycleService;

    @ApiLog("获取当前用户会话列表")
    @GetMapping
    public Result<List<SessionSummaryDto>> listSessions() {
        return Result.success(sessionLifecycleService.listSessions());
    }

    @ApiLog("只读获取当前用户飞书会话列表")
    @GetMapping("/feishu")
    public Result<List<SessionSummaryDto>> listFeishuSessions() {
        return Result.success(sessionLifecycleService.listFeishuSessions());
    }

    @ApiLog("只读获取飞书会话历史")
    @GetMapping("/feishu/{sessionId}")
    public Result<SessionDetailDto> getFeishuSessionDetail(@PathVariable String sessionId) {
        return Result.success(sessionLifecycleService.getFeishuDetail(sessionId));
    }

    @ApiLog("创建聊天会话")
    @PostMapping
    public Result<SessionSummaryDto> createSession(@RequestBody CreateSessionRequest request) {
        return Result.success(sessionLifecycleService.createSession(request));
    }

    @ApiLog("获取聊天会话详情")
    @GetMapping("/{sessionId}")
    public Result<SessionDetailDto> getSessionDetail(@PathVariable String sessionId) {
        return Result.success(sessionLifecycleService.getDetail(sessionId));
    }

    @ApiLog("修改聊天会话标题")
    @PatchMapping("/{sessionId}")
    public Result<SessionSummaryDto> updateSession(
            @PathVariable String sessionId,
            @RequestBody UpdateSessionRequest request
    ) {
        return Result.success(sessionLifecycleService.updateTitle(sessionId, request.title()));
    }

    @ApiLog("根据首个用户问题生成会话标题")
    @PostMapping("/{sessionId}/title/generate")
    public Result<SessionSummaryDto> generateSessionTitle(@PathVariable String sessionId) {
        return Result.success(sessionLifecycleService.generateTitle(sessionId));
    }

    @ApiLog("获取会话权限模式")
    @GetMapping("/{sessionId}/permission-mode")
    public Result<PermissionModeResponse> getPermissionMode(@PathVariable String sessionId) {
        return Result.success(new PermissionModeResponse(sessionLifecycleService.getPermissionMode(sessionId)));
    }

    @ApiLog("修改会话权限模式")
    @PutMapping("/{sessionId}/permission-mode")
    public Result<PermissionModeResponse> updatePermissionMode(
            @PathVariable String sessionId,
            @RequestBody PermissionModeRequest request
    ) {
        if (request == null) throw new IllegalArgumentException("权限模式请求不能为空");
        return Result.success(new PermissionModeResponse(
                sessionLifecycleService.updatePermissionMode(sessionId, request.mode())
        ));
    }

    @ApiLog("删除聊天会话")
    @DeleteMapping("/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        sessionLifecycleService.deleteSession(sessionId);
        return Result.success(null);
    }
}
