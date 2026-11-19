package butvan.agent.agents.tool.impl;

import butvan.agent.agents.tool.shell.CommandShell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {
    @TempDir Path temporaryDirectory;

    @Test
    void executesInConfiguredDirectoryAndReadsUnicode() throws Exception {
        boolean windows = CommandShell.displayName().equals("Windows PowerShell");
        String result = new BashTool(temporaryDirectory.toString()).execute(windows
                ? "Write-Output '中文'; (Get-Location).Path" : "printf '中文\\n'; pwd");
        assertTrue(result.contains("中文"), result);
        assertTrue(result.contains(temporaryDirectory.toRealPath().toString()), result);
    }

    @Test
    void nonzeroExitIsNotReportedAsSuccess() {
        assertTrue(new BashTool(temporaryDirectory.toString()).execute("exit 7").startsWith("Error (exit code 7)"));
    }

    @Test
    void emptyCommandIsRejected() {
        assertTrue(new BashTool().execute(" ").startsWith("Error:"));
    }
}
