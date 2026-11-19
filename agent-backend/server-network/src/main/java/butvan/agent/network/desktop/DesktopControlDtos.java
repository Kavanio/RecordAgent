package butvan.agent.network.desktop;

/**
 * 电脑桌面操控（agent-desktop）设置与环境检测的 HTTP DTO 集合。
 */
public final class DesktopControlDtos {

    private DesktopControlDtos() {}

    /** 当前已保存配置与后端主机平台摘要。 */
    public record StatusResponse(
            boolean enabled,
            String cliPath,
            String hostOs
    ) {}

    /** 更新开关与可选自定义 CLI 路径请求体。 */
    public record UpdateRequest(
            Boolean enabled,
            String cliPath
    ) {}

    /** 有界调用 {@code agent-desktop status} 后的环境自检结果。 */
    public record CheckResponse(
            boolean available,
            String version,
            String platform,
            String accessibilityState,
            String screenRecordingState,
            String message,
            String suggestion
    ) {}

    /** 触发后端宿主机系统权限弹窗/引导后的结果摘要。 */
    public record PermissionRequestResponse(
            boolean triggered,
            String accessibilityState,
            String guideState,
            String message
    ) {}
}
