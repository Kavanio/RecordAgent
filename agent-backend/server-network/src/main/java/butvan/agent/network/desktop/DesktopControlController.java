package butvan.agent.network.desktop;

import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.desktop.DesktopControlDtos.CheckResponse;
import butvan.agent.network.desktop.DesktopControlDtos.PermissionRequestResponse;
import butvan.agent.network.desktop.DesktopControlDtos.StatusResponse;
import butvan.agent.network.desktop.DesktopControlDtos.UpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 电脑桌面操控（agent-desktop）设置与环境自检 HTTP 适配层。
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/agent/desktop-control")
@RequiredArgsConstructor
public class DesktopControlController {

    private final DesktopControlService desktopControlService;

    /** 读取当前已保存的电脑操控配置与后端主机平台。 */
    @ApiLog("查询电脑操控配置")
    @GetMapping
    public Result<StatusResponse> getStatus() {
        return Result.success(desktopControlService.getStatus());
    }

    /** 更新电脑操控启用开关与可选 CLI 路径，写入 ~/.butvan-agent/config.json。 */
    @ApiLog("更新电脑操控配置")
    @PutMapping
    public Result<StatusResponse> update(@RequestBody UpdateRequest request) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("缺少电脑操控启用状态");
        }
        return Result.success(desktopControlService.updateConfig(request.enabled(), request.cliPath()));
    }

    /** 检查后端所在电脑是否安装 agent-desktop 及系统无障碍权限状态。 */
    @ApiLog("检测电脑操控运行环境")
    @GetMapping("/check")
    public Result<CheckResponse> checkEnvironment() {
        return Result.success(desktopControlService.checkEnvironment());
    }

    /** 在 macOS 宿主机上触发系统辅助功能权限弹窗与引导面板。 */
    @ApiLog("请求电脑操控系统权限")
    @PostMapping("/request-permission")
    public Result<PermissionRequestResponse> requestPermission() {
        return Result.success(desktopControlService.requestPermission());
    }
}
