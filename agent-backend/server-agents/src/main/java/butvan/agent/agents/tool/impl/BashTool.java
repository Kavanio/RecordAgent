package butvan.agent.agents.tool.impl;

import butvan.agent.agents.tool.shell.CommandShell;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 原生 AgentScope 终端命令执行工具。
 */
@Slf4j
public class BashTool {

    private final String workDir;

    public BashTool() {
        this.workDir = System.getProperty("user.dir");
    }

    public BashTool(String workDir) {
        this.workDir = workDir != null ? workDir : System.getProperty("user.dir");
    }

    @Tool(name = "custom_bash", description = "在后端所在主机执行命令：Windows 使用 PowerShell，Linux/macOS 使用 sh；容器部署仅操作容器。支持系统查询与代码任务。")
    public String execute(
            @ToolParam(name = "command", description = "要执行的终端 Shell 指令") String command
    ) {
        if (command == null || command.isBlank()) {
            return "Error: Command parameter cannot be empty.";
        }

        Process process = null;
        CompletableFuture<String> outputFuture = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(CommandShell.command(command));
            processBuilder.directory(new File(workDir));
            processBuilder.redirectErrorStream(true);

            process = processBuilder.start();
            Process runningProcess = process;

            // 防卡死关键 1：主动关闭 stdin 标准输入流，防止子进程因交互性命令卡死等待用户输入
            process.getOutputStream().close();

            // 防卡死关键 2：异步线程读取标准输出流，防止缓冲区满导致父子线程死锁
            outputFuture = CompletableFuture.supplyAsync(() -> {
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(runningProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (Exception exception) {
                    log.debug("读取已结束命令的输出流失败", exception);
                }
                return output.toString();
            });

            // 防卡死关键 3：设置 15 秒合理超时限制（避免长卡 120 秒）
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                terminateProcessTree(process);
                outputFuture.cancel(true);
                return "Error: Command execution timed out (15s limit).";
            }

            String result = outputFuture.get(3, TimeUnit.SECONDS);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return "Error (exit code " + exitCode + "):\n" + result;
            }

            return result.isBlank() ? "Command executed successfully with no output." : result;
        } catch (InterruptedException exception) {
            terminateProcessTree(process);
            if (outputFuture != null) outputFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令执行已取消", exception);
        } catch (Exception e) {
            terminateProcessTree(process);
            if (outputFuture != null) outputFuture.cancel(true);
            return "Error: Failed to execute bash command: " + e.getMessage();
        }
    }

    /** 取消或超时时同时终止 shell 与它启动的子进程。 */
    private void terminateProcessTree(Process process) {
        if (process == null || !process.isAlive()) return;
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroy();
        });
        process.destroy();
        if (process.isAlive()) {
            process.descendants().forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
            process.destroyForcibly();
        }
    }
}
