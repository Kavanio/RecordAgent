package butvan.agent.network.material.screen;

import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.agent.AgentImages;
import com.sun.jna.Platform;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 平台截图实现，固定范围为当前后端宿主的主屏幕。 */
final class ScreenCapture {
    private static final long MAX_PIXELS = 40_000_000;

    AgentImageAttachment primaryDisplay() throws IOException, InterruptedException {
        if (Platform.isWindows()) return captureWindows();
        if (Platform.isMac()) return captureMac();
        throw new IllegalStateException("截图目前支持 Windows 和 macOS 图形桌面。");
    }

    private AgentImageAttachment captureWindows() throws IOException {
        WindowsScreenDesktop.requireUnlockedSession();
        var user32 = com.sun.jna.platform.win32.User32.INSTANCE;
        // SM_CXSCREEN / SM_CYSCREEN 是主显示器尺寸，先检查再分配位图。
        long pixels = (long) user32.GetSystemMetrics(0) * user32.GetSystemMetrics(1);
        if (pixels <= 0 || pixels > MAX_PIXELS) throw new IllegalStateException("主屏幕尺寸不受支持。");
        var image = com.sun.jna.platform.win32.GDI32Util.getScreenshot(user32.GetDesktopWindow());
        WindowsScreenDesktop.requireUnlockedSession();
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
            return AgentImages.parse(output.toByteArray());
        }
    }

    private AgentImageAttachment captureMac() throws IOException, InterruptedException {
        MacScreenDesktop.requireUnlockedConsole();
        if (!MacScreenDesktop.hasCapturePermission()) {
            throw new IllegalStateException("macOS 未授予屏幕录制权限，请在系统设置中为启动后端的应用开启权限。");
        }
        Path image = Files.createTempFile("butvan-screen-", ".png");
        Process process = null;
        try {
            process = new ProcessBuilder("/usr/sbin/screencapture", "-x", "-m", "-t", "png", image.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IllegalStateException("截图超时。");
            if (process.exitValue() != 0) throw new IOException("screencapture returned non-zero status");
            MacScreenDesktop.requireUnlockedConsole();
            try (var input = Files.newInputStream(image)) {
                return AgentImages.parse(input.readNBytes(AgentImageAttachment.MAX_BYTES + 1));
            }
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(image);
        }
    }
}
