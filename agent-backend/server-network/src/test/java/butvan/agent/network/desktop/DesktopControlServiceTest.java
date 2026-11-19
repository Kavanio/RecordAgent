package butvan.agent.network.desktop;

import butvan.agent.agents.config.DesktopControlProperties;
import butvan.agent.network.desktop.DesktopCliRunner.CliExecutionResult;
import butvan.agent.network.desktop.DesktopControlDtos.CheckResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopControlServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blocksToolExecutionWhenDisabledWithoutLaunchingCli(@TempDir Path tempDir) {
        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, tempDir.resolve("config.json"));
        RecordingRunner runner = new RecordingRunner(CliExecutionResult.notFound("should not run"));
        DesktopControlService service = new DesktopControlService(properties, runner, objectMapper);
        DesktopControlTool tool = new DesktopControlTool(service);

        ToolResultBlock result = tool.snapshot(new DesktopControlTool.SnapshotRequest(
                "Finder", null, null, null, true, true, true, null, null), context("sess-1"));

        assertTrue(extractText(result).contains("电脑桌面操控尚未开启"));
        assertTrue(runner.recordedCalls.isEmpty());
    }

    @Test
    void buildsExpectedCliArgsAndPipesTextViaStdinWhenEnabled(@TempDir Path tempDir) {
        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, tempDir.resolve("config.json"));
        properties.updateConfig(true, "");

        RecordingRunner runner = new RecordingRunner(
                new CliExecutionResult(true, false, 0, "{\"ok\":true}", "", false, null));
        DesktopControlService service = new DesktopControlService(properties, runner, objectMapper);
        DesktopControlTool tool = new DesktopControlTool(service);

        tool.snapshot(new DesktopControlTool.SnapshotRequest(
                "Finder", null, "@s1:e2", "window", true, true, true, 6, false), context("abc/123"));

        assertEquals(1, runner.recordedCalls.size());
        RecordedCall snapshotCall = runner.recordedCalls.get(0);
        assertEquals(List.of("--session", "chat-abc_123", "snapshot", "--app", "Finder",
                "--root", "@s1:e2", "--surface", "window", "--max-depth", "6",
                "--skeleton", "-i", "--compact"), snapshotCall.args());

        tool.input(new DesktopControlTool.InputRequest(
                "type", "@s1:e9", "hello world", null, null, null, false), context("abc/123"));

        RecordedCall typeCall = runner.recordedCalls.get(1);
        assertEquals(List.of("--session", "chat-abc_123", "type", "--stdin", "@s1:e9"), typeCall.args());
        assertEquals("hello world", typeCall.stdinText());

        tool.input(new DesktopControlTool.InputRequest(
                "press", null, null, "return", "Google Chrome", "w-17804", null), context("abc/123"));

        RecordedCall pressCall = runner.recordedCalls.get(2);
        assertEquals(List.of("--session", "chat-abc_123", "--headed", "press",
                "--app", "Google Chrome", "--window-id", "w-17804", "return"), pressCall.args());

        tool.input(new DesktopControlTool.InputRequest(
                "type", "@s1:e5", "https://chatgpt.com", null, null, null, true), context("abc/123"));

        RecordedCall urlTypeCall = runner.recordedCalls.get(3);
        assertEquals("https://chatgpt.com/ ", urlTypeCall.stdinText());

        ToolResultBlock openUrlResult = tool.window(new DesktopControlTool.WindowRequest(
                "open-url", "Google Chrome", "chatgpt.com", null, null, null, null, null, null), context("abc/123"));
        assertTrue(extractText(openUrlResult).contains("\"command\":\"open-url\""));
        assertEquals(1, runner.openedUrls.size());
        assertEquals("Google Chrome|https://chatgpt.com", runner.openedUrls.get(0));
    }

    @Test
    void rejectsUnsafeOrMalformedToolArguments(@TempDir Path tempDir) {
        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, tempDir.resolve("config.json"));
        properties.updateConfig(true, "");

        RecordingRunner runner = new RecordingRunner(
                new CliExecutionResult(true, false, 0, "{\"ok\":true}", "", false, null));
        DesktopControlService service = new DesktopControlService(properties, runner, objectMapper);
        DesktopControlTool tool = new DesktopControlTool(service);

        ToolResultBlock badRef = tool.action(new DesktopControlTool.ActionRequest(
                "click", "not-a-ref", null, null, null, false), context("s1"));
        assertTrue(extractText(badRef).contains("@ref"));

        ToolResultBlock badFlag = tool.window(new DesktopControlTool.WindowRequest(
                "close-app", "--force", null, null, null, null, null, null, null), context("s1"));
        assertTrue(extractText(badFlag).contains("格式非法"));

        ToolResultBlock badUrl = tool.window(new DesktopControlTool.WindowRequest(
                "open-url", "Google Chrome", "file:///etc/passwd", null, null, null, null, null, null), context("s1"));
        assertTrue(extractText(badUrl).contains("http:// 或 https://"));
        assertTrue(runner.recordedCalls.isEmpty());
        assertTrue(runner.openedUrls.isEmpty());
    }

    @Test
    void parsesCheckEnvironmentResponseForGrantedAndDenied(@TempDir Path tempDir) {
        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, tempDir.resolve("config.json"));
        String statusJson = """
                {
                  "version": "2.6",
                  "ok": true,
                  "command": "status",
                  "data": {
                    "version": "1.3.1",
                    "platform": "macos",
                    "permissions": {
                      "accessibility": { "state": "granted" },
                      "screen_recording": { "state": "granted" }
                    }
                  }
                }
                """;
        RecordingRunner runner = new RecordingRunner(
                new CliExecutionResult(true, false, 0, statusJson, "", false, null));
        DesktopControlService service = new DesktopControlService(properties, runner, objectMapper);

        CheckResponse check = service.checkEnvironment();
        assertTrue(check.available());
        assertEquals("1.3.1", check.version());
        assertEquals("macos", check.platform());
        assertEquals("granted", check.accessibilityState());
        assertTrue(check.message().contains("已就绪"));
    }

    @Test
    void resolvesPlatformSpecificExecutableCandidates() {
        List<String> win = DesktopCliRunner.resolveExecutableCandidates("", "windows");
        assertEquals(List.of("agent-desktop.exe", "agent-desktop.cmd", "agent-desktop"), win);

        List<String> custom = DesktopCliRunner.resolveExecutableCandidates("C:\\tools\\agent-desktop.exe", "windows");
        assertEquals(List.of("C:\\tools\\agent-desktop.exe"), custom);

        List<String> mac = DesktopCliRunner.resolveExecutableCandidates("", "macos");
        assertFalse(mac.isEmpty());
        assertEquals("agent-desktop", mac.get(0));
    }

    @Test
    void triggersMacosPermissionRequestAndGuideWhenDenied(@TempDir Path tempDir) {
        DesktopControlProperties properties = new DesktopControlProperties(objectMapper, tempDir.resolve("config.json"));
        String deniedJson = """
                {
                  "ok": true,
                  "data": {
                    "accessibility": { "state": "denied" },
                    "guide": "started"
                  }
                }
                """;
        RecordingRunner runner = new RecordingRunner(
                new CliExecutionResult(true, false, 0, deniedJson, "", false, null));
        DesktopControlService service = new DesktopControlService(properties, runner, objectMapper);

        var response = service.requestPermission();
        assertTrue(response.triggered());
        assertEquals("started", response.guideState());
        assertEquals(2, runner.recordedCalls.size());
        assertEquals(List.of("permissions", "--request"), runner.recordedCalls.get(0).args());
        assertEquals(List.of("permissions", "--guide"), runner.recordedCalls.get(1).args());
    }

    private static String extractText(ToolResultBlock block) {
        return block.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }

    private static RuntimeContext context(String sessionId) {
        return RuntimeContext.builder().userId("u1").sessionId(sessionId).build();
    }

    private record RecordedCall(String cliPath, List<String> args, String stdinText, Duration timeout) {}

    private static final class RecordingRunner extends DesktopCliRunner {
        private final CliExecutionResult fixedResult;
        private final List<RecordedCall> recordedCalls = new ArrayList<>();
        private final List<String> openedUrls = new ArrayList<>();

        private RecordingRunner(CliExecutionResult fixedResult) {
            this.fixedResult = fixedResult;
        }

        @Override
        public String hostOs() {
            return "macos";
        }

        @Override
        public CliExecutionResult execute(String configuredCliPath, List<String> arguments, String stdinText, Duration timeout) {
            recordedCalls.add(new RecordedCall(configuredCliPath, List.copyOf(arguments), stdinText, timeout));
            return fixedResult;
        }

        @Override
        public CliExecutionResult openUrl(String app, String validatedHttpUrl, Duration timeout) {
            openedUrls.add(app + "|" + validatedHttpUrl);
            return fixedResult;
        }
    }
}
