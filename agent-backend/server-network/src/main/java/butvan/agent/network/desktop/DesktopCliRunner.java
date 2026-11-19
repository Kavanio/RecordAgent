package butvan.agent.network.desktop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 跨平台 {@code agent-desktop} CLI 进程执行器。
 *
 * <p>直接通过 {@link ProcessBuilder} 参数数组传参，不经过 Shell 拼接，避免注入及 Windows PowerShell 的 {@code @ref} 参数误解析。</p>
 */
@Slf4j
@Component
public class DesktopCliRunner {

    static final int MAX_STDOUT_CHARS = 24_000;
    static final int MAX_STDERR_CHARS = 4_000;

    /** 单次 CLI 调用结果。 */
    public record CliExecutionResult(
            boolean started,
            boolean timedOut,
            int exitCode,
            String stdout,
            String stderr,
            boolean stdoutTruncated,
            String failureMessage
    ) {
        public static CliExecutionResult notFound(String message) {
            return new CliExecutionResult(false, false, -1, "", "", false, message);
        }

        public static CliExecutionResult timeout(String message) {
            return new CliExecutionResult(true, true, -1, "", "", false, message);
        }
    }

    /** 标识工具执行因会话取消而被中断。 */
    public static final class CommandCancelledException extends RuntimeException {
        public CommandCancelledException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 返回归一化后的当前宿主机操作系统标识（macos / windows / linux）。 */
    public String hostOs() {
        return normalizeOs(System.getProperty("os.name", ""));
    }

    static String normalizeOs(String osName) {
        String lower = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (lower.contains("mac") || lower.contains("darwin")) return "macos";
        if (lower.startsWith("windows")) return "windows";
        return "linux";
    }

    /**
     * 执行一条 {@code agent-desktop} 命令。
     *
     * @param configuredCliPath 用户配置的自定义路径（可为空）
     * @param arguments         子命令与参数列表
     * @param stdinText         需要通过标准输入传递的 UTF-8 文本（可为 null）
     * @param timeout           命令最长执行时间
     */
    public CliExecutionResult execute(
            String configuredCliPath,
            List<String> arguments,
            String stdinText,
            Duration timeout
    ) {
        List<String> candidates = resolveExecutableCandidates(configuredCliPath, hostOs());
        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? Duration.ofSeconds(15)
                : timeout;

        IOException lastStartError = null;
        for (String executable : candidates) {
            try {
                return runSingleCandidate(executable, arguments, stdinText, effectiveTimeout);
            } catch (ProcessStartException exception) {
                lastStartError = exception.ioCause;
            }
        }

        String hint = configuredCliPath != null && !configuredCliPath.isBlank()
                ? "无法启动配置的 agent-desktop 路径（" + configuredCliPath + "），请核对文件是否存在且具备执行权限。"
                : "当前电脑未找到 agent-desktop 命令，请先安装（npm install -g agent-desktop）或在设置中指定 CLI 路径。";
        log.debug("agent-desktop 启动失败：candidates={}, error={}", candidates,
                lastStartError == null ? "none" : lastStartError.getMessage());
        return CliExecutionResult.notFound(hint);
    }

    /** 按宿主机平台构造有序可执行文件候选列表。 */
    static List<String> resolveExecutableCandidates(String configuredCliPath, String normalizedOs) {
        if (configuredCliPath != null && !configuredCliPath.isBlank()) {
            return List.of(configuredCliPath.strip());
        }
        if ("windows".equals(normalizedOs)) {
            return List.of("agent-desktop.exe", "agent-desktop.cmd", "agent-desktop");
        }
        List<String> candidates = new ArrayList<>();
        candidates.add("agent-desktop");
        if ("macos".equals(normalizedOs)) {
            addIfExecutable(candidates, "/opt/homebrew/bin/agent-desktop");
            addIfExecutable(candidates, "/usr/local/bin/agent-desktop");
        }
        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            addIfExecutable(candidates, Path.of(userHome, ".local", "bin", "agent-desktop").toString());
        }
        return List.copyOf(candidates);
    }

    private static void addIfExecutable(List<String> candidates, String candidatePath) {
        try {
            Path path = Path.of(candidatePath);
            if (Files.isRegularFile(path) && Files.isExecutable(path)) {
                candidates.add(candidatePath);
            }
        } catch (RuntimeException ignored) {
            // 忽略非法路径或权限查询异常
        }
    }

    private CliExecutionResult runSingleCandidate(
            String executable,
            List<String> arguments,
            String stdinText,
            Duration timeout
    ) throws ProcessStartException {
        List<String> command = new ArrayList<>(1 + (arguments == null ? 0 : arguments.size()));
        command.add(executable);
        if (arguments != null) command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        Process process;
        try {
            process = builder.start();
        } catch (IOException exception) {
            throw new ProcessStartException(exception);
        }

        CompletableFuture<StreamCapture> stdoutFuture = null;
        CompletableFuture<StreamCapture> stderrFuture = null;
        try {
            stdoutFuture = CompletableFuture.supplyAsync(() -> readStreamBounded(process.getInputStream(), MAX_STDOUT_CHARS));
            stderrFuture = CompletableFuture.supplyAsync(() -> readStreamBounded(process.getErrorStream(), MAX_STDERR_CHARS));

            try (OutputStream stdin = process.getOutputStream()) {
                if (stdinText != null) {
                    stdin.write(stdinText.getBytes(StandardCharsets.UTF_8));
                    stdin.flush();
                }
            } catch (IOException exception) {
                log.debug("向 agent-desktop 写入 stdin 时子进程已提前关闭：{}", exception.getMessage());
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateProcessTree(process);
                cancelFuture(stdoutFuture);
                cancelFuture(stderrFuture);
                return CliExecutionResult.timeout("agent-desktop 命令执行超时（上限 " + timeout.toSeconds() + " 秒）。");
            }

            StreamCapture stdout = awaitCapture(stdoutFuture);
            StreamCapture stderr = awaitCapture(stderrFuture);
            return new CliExecutionResult(
                    true,
                    false,
                    process.exitValue(),
                    stdout.text(),
                    stderr.text(),
                    stdout.truncated(),
                    null
            );
        } catch (InterruptedException exception) {
            terminateProcessTree(process);
            cancelFuture(stdoutFuture);
            cancelFuture(stderrFuture);
            Thread.currentThread().interrupt();
            throw new CommandCancelledException("桌面操作命令已取消", exception);
        } catch (RuntimeException exception) {
            terminateProcessTree(process);
            cancelFuture(stdoutFuture);
            cancelFuture(stderrFuture);
            throw exception;
        }
    }

    private static StreamCapture readStreamBounded(InputStream inputStream, int maxChars) {
        StringBuilder builder = new StringBuilder();
        boolean truncated = false;
        char[] buffer = new char[2048];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (builder.length() < maxChars) {
                    int remaining = maxChars - builder.length();
                    int toAppend = Math.min(read, remaining);
                    builder.append(buffer, 0, toAppend);
                    if (toAppend < read) truncated = true;
                } else {
                    truncated = true;
                }
            }
        } catch (IOException ignored) {
            // 子进程结束或被中断时正常退出读取
        }
        return new StreamCapture(builder.toString(), truncated);
    }

    private static StreamCapture awaitCapture(CompletableFuture<StreamCapture> future) throws InterruptedException {
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            throw exception;
        } catch (Exception ignored) {
            return new StreamCapture("", false);
        }
    }

    private static void cancelFuture(CompletableFuture<?> future) {
        if (future != null) future.cancel(true);
    }

    private static void terminateProcessTree(Process process) {
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroy();
        });
        process.destroy();
        if (process.isAlive()) {
            try {
                process.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (process.isAlive()) {
            process.descendants().forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
            process.destroyForcibly();
        }
    }

    private record StreamCapture(String text, boolean truncated) {}

    private static final class ProcessStartException extends Exception {
        private final IOException ioCause;

        private ProcessStartException(IOException ioCause) {
            super(ioCause);
            this.ioCause = ioCause;
        }
    }
}
