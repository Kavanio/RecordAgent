package butvan.agent.agents.config;

/**
 * 电脑桌面操控（agent-desktop）的用户级配置快照。
 *
 * <p>对应 {@code ~/.butvan-agent/config.json} 中的 {@code desktopControl} 节点。</p>
 *
 * @param enabled 是否启用后端所在电脑的桌面操控能力（默认 false）
 * @param cliPath 自定义 agent-desktop 可执行文件路径；为空时从系统 PATH 解析
 */
public record DesktopControlConfigData(
        boolean enabled,
        String cliPath
) {

    public DesktopControlConfigData {
        cliPath = cliPath == null ? "" : cliPath.strip();
    }

    /** 返回默认关闭态配置。 */
    public static DesktopControlConfigData disabled() {
        return new DesktopControlConfigData(false, "");
    }
}
