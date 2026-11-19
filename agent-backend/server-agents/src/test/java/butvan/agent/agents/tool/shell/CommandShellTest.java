package butvan.agent.agents.tool.shell;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandShellTest {
    @Test
    void windowsPreservesUnicodeMultilineAndQuotesWithoutShellInterpolation() {
        String script = "Write-Output '中文 \"引号\"'\nGet-Location";
        List<String> command = CommandShell.command(script, "Windows 11", null);
        assertEquals(List.of("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand"),
                command.subList(0, 5));
        String decoded = new String(Base64.getDecoder().decode(command.getLast()), StandardCharsets.UTF_16LE);
        assertTrue(decoded.contains(script));
        assertTrue(decoded.contains("UTF8Encoding"));
        assertTrue(decoded.contains("exit $LASTEXITCODE"));
    }

    @Test
    void unixAndDarwinUseShAndPassScriptUnchanged() {
        for (String os : List.of("Linux", "Mac OS X", "Darwin")) {
            assertEquals(List.of("sh", "-c", "printf 'a b'"), CommandShell.command("printf 'a b'", os, null));
        }
    }
}
