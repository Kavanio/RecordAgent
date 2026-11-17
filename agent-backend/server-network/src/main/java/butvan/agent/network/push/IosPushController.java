package butvan.agent.network.push;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.push.IosPushDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * iOS 主动推送、设备令牌登记、APNs 配置与前台 SSE 实时提醒接口。
 */
@RestController
@RequestMapping("/agent/ios-push")
@RequiredArgsConstructor
public class IosPushController {
    private final IosPushService pushService;
    private final CurrentUserProvider currentUser;

    @ApiLog("查询 iOS 主动推送状态与设备列表")
    @GetMapping("/status")
    public Result<IosPushStatusResponse> status() {
        return Result.success(pushService.status(currentUser.currentUserId()));
    }

    @ApiLog("保存 iOS APNs 服务端推送配置")
    @PutMapping("/apns-config")
    public Result<IosPushStatusResponse> saveApnsConfig(@RequestBody SaveApnsConfigRequest input) {
        return Result.success(pushService.saveApnsConfig(currentUser.currentUserId(), input));
    }

    @ApiLog("登记或更新 iOS 设备与主动推送偏好")
    @PostMapping("/devices")
    public Result<IosPushStatusResponse> registerDevice(@RequestBody RegisterDeviceRequest input) {
        return Result.success(pushService.registerDevice(currentUser.currentUserId(), input));
    }

    @ApiLog("注销 iOS 设备主动推送登记")
    @DeleteMapping("/devices/{deviceId}")
    public Result<IosPushStatusResponse> removeDevice(@PathVariable String deviceId) {
        return Result.success(pushService.removeDevice(currentUser.currentUserId(), deviceId));
    }

    @ApiLog("查询 iOS 待展示或近期主动推送通知")
    @GetMapping("/notices")
    public Result<List<IosNoticeResponse>> notices(
            @RequestParam(defaultValue = "false") boolean includeAcknowledged
    ) {
        return Result.success(pushService.notices(currentUser.currentUserId(), includeAcknowledged));
    }

    @ApiLog("确认已弹出或已读取 iOS 主动通知")
    @PostMapping("/notices/{id}/ack")
    public Result<IosPushStatusResponse> acknowledge(@PathVariable String id) {
        return Result.success(pushService.acknowledgeNotice(currentUser.currentUserId(), id));
    }

    @ApiLog("发送 iOS 主动推送测试通知")
    @PostMapping("/test")
    public Result<TestPushResponse> test(@RequestBody(required = false) TestPushRequest input) {
        return Result.success(pushService.sendTestNotice(currentUser.currentUserId(), input));
    }

    @ApiLog("订阅 iOS 主动推送实时通知流")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return pushService.subscribe(currentUser.currentUserId());
    }
}
