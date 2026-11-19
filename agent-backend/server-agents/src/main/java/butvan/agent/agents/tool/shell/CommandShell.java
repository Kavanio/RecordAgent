package butvan.agent.agents.tool.shell;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** 命令执行与环境提示共用的 Shell 选择；Windows 不依赖 Git Bash 或 WSL。 */
public final class CommandShell {

    private CommandShell() {}

    /** 当前实际使用的 Shell，避免提示词与工具运行环境不一致。 */
    public static String displayName() {
        return isWindows(System.getProperty("os.name", "")) ? "Windows PowerShell" : "sh";
    }

    /** 构造原生进程参数；编码脚本保留中文、换行和引号，不通过额外 Shell 拼接。 */
    public static List<String> command(String script) {
        return command(script, System.getProperty("os.name", ""), System.getenv("SystemRoot"));
    }

    static List<String> command(String script, String os, String systemRoot) {
        if (!isWindows(os)) return List.of("sh", "-c", script);
        String executable = systemRoot == null || systemRoot.isBlank() ? "powershell.exe"
                : Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        // 原生命令失败保留退出码，PowerShell 异常变为失败；UTF-8 对齐 Java 输出读取。
        String wrapped = "$ErrorActionPreference = 'Stop'\n"
                + "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false\n"
                + "$OutputEncoding = [Console]::OutputEncoding\n"
                + "$global:LASTEXITCODE = 0\n& {\n" + script
                + "\n}\nif (-not $?) { exit 1 }; exit $LASTEXITCODE\n";
        String encoded = Base64.getEncoder().encodeToString(wrapped.getBytes(StandardCharsets.UTF_16LE));
        return List.of(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
    }

    private static boolean isWindows(String os) {
        return os.toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
