package butvan.agent.network.material.screen;

import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.agent.AgentImages;
import com.sun.jna.Platform;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** 平台截图实现，固定范围为当前后端宿主的主屏幕。 */
final class ScreenCapture {
    private static final long MAX_PIXELS = 45_000_000;
    private static final long MAX_RAW_PNG_BYTES = 80L * 1024 * 1024;

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
        return encodeWithinLimit(image);
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
            long fileSize = Files.size(image);
            if (fileSize <= AgentImageAttachment.MAX_BYTES) {
                return AgentImages.parse(Files.readAllBytes(image));
            }
            if (fileSize > MAX_RAW_PNG_BYTES) {
                throw new IllegalStateException("主屏幕截图体积过大，无法发送。");
            }
            BufferedImage decoded = ImageIO.read(image.toFile());
            if (decoded == null) throw new IOException("无法解码主屏截图");
            long pixels = (long) decoded.getWidth() * decoded.getHeight();
            if (pixels <= 0 || pixels > MAX_PIXELS) {
                throw new IllegalStateException("主屏幕尺寸不受支持。");
            }
            return compressToJpegWithinLimit(decoded);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(image);
        }
    }

    static AgentImageAttachment encodeWithinLimit(BufferedImage image) throws IOException {
        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
            if (output.size() <= AgentImageAttachment.MAX_BYTES) {
                return AgentImages.parse(output.toByteArray());
            }
        }
        return compressToJpegWithinLimit(image);
    }

    static AgentImageAttachment compressToJpegWithinLimit(BufferedImage source) throws IOException {
        double[] scales = {1.0, 0.75, 0.55, 0.4};
        float[] qualities = {0.88f, 0.74f};
        for (double scale : scales) {
            BufferedImage rgb = toRgbScaled(source, scale);
            for (float quality : qualities) {
                byte[] jpeg = writeJpeg(rgb, quality);
                if (jpeg.length <= AgentImageAttachment.MAX_BYTES) {
                    return AgentImages.parse(jpeg);
                }
            }
        }
        throw new IllegalStateException("主屏幕截图超过 10 MiB 上限，无法发送。");
    }

    private static BufferedImage toRgbScaled(BufferedImage source, double scale) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        if (scale == 1.0 && source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static byte[] writeJpeg(BufferedImage rgb, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("JPEG writer unavailable");
        ImageWriter writer = writers.next();
        try (var output = new ByteArrayOutputStream();
             var imageOut = new MemoryCacheImageOutputStream(output)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.setOutput(imageOut);
            writer.write(null, new IIOImage(rgb, null, null), param);
            imageOut.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
