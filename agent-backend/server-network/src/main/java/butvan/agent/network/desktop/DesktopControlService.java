package butvan.agent.network.desktop;

import butvan.agent.agents.config.DesktopControlConfigData;
import butvan.agent.agents.config.DesktopControlProperties;
import butvan.agent.network.desktop.DesktopCliRunner.CliExecutionResult;
import butvan.agent.network.desktop.DesktopControlDtos.CheckResponse;
import butvan.agent.network.desktop.DesktopControlDtos.StatusResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 电脑桌面操控领域服务：管理 config.json 配置、环境自检及工具执行协调。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DesktopControlService {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(5);
    private static final long WRITE_LOCK_WAIT_SECONDS = 10;

    private final DesktopControlProperties properties;
    private final DesktopCliRunner cliRunner;
    private final ObjectMapper objectMapper;

    /** 单机 GUI 写操作互斥锁，防止并发点击或输入互相抢占焦点。 */
    private final Semaphore writeLock = new Semaphore(1);

    /** 读取 config.json 中当前保存的配置与后端主机系统类型（不触发外部进程）。 */
    public StatusResponse getStatus() {
        DesktopControlConfigData config = properties.load();
        return new StatusResponse(config.enabled(), config.cliPath(), cliRunner.hostOs());
    }

    /** 更新 config.json 的启用状态及可选自定义 CLI 路径。 */
    public StatusResponse updateConfig(boolean enabled, String cliPath) {
        DesktopControlConfigData updated = properties.updateConfig(enabled, cliPath);
        return new StatusResponse(updated.enabled(), updated.cliPath(), cliRunner.hostOs());
    }

    /** 执行一次有界只读环境检查（调用 {@code agent-desktop status}），返回版本与系统权限摘要。 */
    public CheckResponse checkEnvironment() {
        DesktopControlConfigData config = properties.load();
        CliExecutionResult result = cliRunner.execute(config.cliPath(), List.of("status"), null, CHECK_TIMEOUT);
        if (!result.started()) {
            return new CheckResponse(false, "", cliRunner.hostOs(), "unknown", "unknown",
                    result.failureMessage(), "请在后端所在电脑安装 agent-desktop（npm install -g agent-desktop），或在上方填写可执行文件路径。");
        }
        if (result.timedOut()) {
            return new CheckResponse(false, "", cliRunner.hostOs(), "unknown", "unknown",
                    result.failureMessage(), "请确认后端所在电脑处于已解锁的图形桌面会话中，再重试检测。");
        }

        String raw = result.stdout().strip();
        if (raw.isEmpty()) {
            String fallback = result.stderr().isBlank() ? "agent-desktop 未返回状态信息" : result.stderr().strip();
            return new CheckResponse(false, "", cliRunner.hostOs(), "unknown", "unknown",
                    fallback, "请在本机终端手动运行 agent-desktop status 排查。");
        }

        try {
            JsonNode envelope = objectMapper.readTree(raw);
            if (!envelope.path("ok").asBoolean(false)) {
                String errorMsg = envelope.path("error").path("message").asText("agent-desktop 返回错误");
                String suggestion = envelope.path("error").path("suggestion").asText("");
                return new CheckResponse(false, "", cliRunner.hostOs(), "unknown", "unknown",
                        errorMsg, suggestion);
            }

            JsonNode data = envelope.path("data");
            String version = data.path("version").asText("");
            String platform = data.path("platform").asText(cliRunner.hostOs());
            JsonNode permissions = data.path("permissions");
            String accessibilityState = permissions.path("accessibility").path("state").asText("unknown");
            String screenRecordingState = permissions.path("screen_recording").path("state").asText("unknown");
            String suggestion = permissions.path("accessibility").path("suggestion").asText("");

            String message = buildCheckSummary(platform, version, accessibilityState);
            return new CheckResponse(true, version, platform, accessibilityState, screenRecordingState, message, suggestion);
        } catch (Exception exception) {
            log.warn("解析 agent-desktop status 输出失败：{}", exception.getMessage());
            return new CheckResponse(false, "", cliRunner.hostOs(), "unknown", "unknown",
                    "无法解析 agent-desktop 返回的状态结果（退出码 " + result.exitCode() + "）。",
                    "请确认配置的 CLI 路径指向正版 agent-desktop 可执行文件。");
        }
    }

    /**
     * 供 Agent 工具调用的执行入口：实时检查 config.json 开关，按需附加会话命名空间并对写操作串行加锁。
     */
    public ToolInvocationOutcome invokeForTool(
            String sessionId,
            List<String> subcommandArgs,
            String stdinText,
            Duration timeout,
            boolean writeOperation,
            boolean attachSession
    ) {
        DesktopControlConfigData config = properties.load();
        if (!config.enabled()) {
            return ToolInvocationOutcome.error(
                    "电脑桌面操控尚未开启。请先在桌面端「设置 -> 后端连接」或手机端「设置 -> 电脑操控」中启用，"
                            + "或在 ~/.butvan-agent/config.json 中设置 desktopControl.enabled=true。");
        }

        List<String> fullArgs = new ArrayList<>(subcommandArgs.size() + 2);
        if (attachSession) {
            String normalizedSession = sanitizeSessionScope(sessionId);
            if (normalizedSession != null) {
                fullArgs.add("--session");
                fullArgs.add(normalizedSession);
            }
        }
        fullArgs.addAll(subcommandArgs);

        boolean acquired = false;
        try {
            if (writeOperation) {
                acquired = writeLock.tryAcquire(WRITE_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
                if (!acquired) {
                    return ToolInvocationOutcome.error("当前有另一项桌面操作正在执行，请稍后再试。");
                }
            }

            CliExecutionResult result = cliRunner.execute(config.cliPath(), fullArgs, stdinText, timeout);
            if (!result.started() || result.timedOut()) {
                return ToolInvocationOutcome.error(result.failureMessage());
            }

            String stdout = result.stdout().strip();
            String stderr = result.stderr().strip();
            if (stdout.isEmpty()) {
                if (result.exitCode() == 0) {
                    return ToolInvocationOutcome.success("{\"ok\":true}");
                }
                return ToolInvocationOutcome.error(stderr.isEmpty()
                        ? "agent-desktop 执行失败（退出码 " + result.exitCode() + "）。"
                        : "agent-desktop 执行失败：" + stderr);
            }

            if (result.stdoutTruncated()) {
                String notice = "\n[注意：输出已超过 " + DesktopCliRunner.MAX_STDOUT_CHARS
                        + " 字符被截断。请对 snapshot 使用 skeleton=true、interactiveOnly=true、compact=true 或指定 rootRef / app 缩小范围。]";
                return ToolInvocationOutcome.success(stdout + notice);
            }

            return ToolInvocationOutcome.success(stdout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolInvocationOutcome.error("桌面操作已取消。");
        } catch (DesktopCliRunner.CommandCancelledException exception) {
            return ToolInvocationOutcome.error("桌面操作已取消。");
        } finally {
            if (acquired) writeLock.release();
        }
    }

    /** 将聊天 sessionId 归一化为合法的 agent-desktop session 名称（1-64 字符，仅字母/数字/-/_）。 */
    static String sanitizeSessionScope(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < sessionId.length() && builder.length() < 48; i++) {
            char ch = sessionId.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_') {
                builder.append(ch);
            } else {
                builder.append('_');
            }
        }
        String sanitized = builder.toString();
        return sanitized.isEmpty() ? null : "chat-" + sanitized;
    }

    private static String buildCheckSummary(String platform, String version, String accessibilityState) {
        String versionPart = version.isBlank() ? "" : " v" + version;
        if ("granted".equalsIgnoreCase(accessibilityState)) {
            return "已检测到 agent-desktop" + versionPart + "（" + platform + "），系统无障碍权限已就绪。";
        }
        if ("denied".equalsIgnoreCase(accessibilityState)) {
            return "已检测到 agent-desktop" + versionPart + "（" + platform + "），但系统辅助功能权限未授予。";
        }
        return "已检测到 agent-desktop" + versionPart + "（" + platform + "），辅助功能权限状态：" + accessibilityState + "。";
    }

    /** 工具调用封装结果。 */
    public record ToolInvocationOutcome(boolean ok, String content) {
        public static ToolInvocationOutcome success(String content) {
            return new ToolInvocationOutcome(true, content);
        }

        public static ToolInvocationOutcome error(String message) {
            return new ToolInvocationOutcome(false, message);
        }
    }
}
