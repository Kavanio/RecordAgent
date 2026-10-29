package butvan.agent.agents.context;

import butvan.agent.agents.memory.MemoryRecallPort;
import butvan.agent.agents.storage.AgentStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import static org.junit.jupiter.api.Assertions.*;

class LegacyMemoryFlushExclusionTest {
    @TempDir Path root;
    @Test void generatedInterpretationIsNotAutomaticallyRecalledButOriginalFileIsPreserved() throws Exception {
        var storage = new AgentStorageProperties(root);
        var file = storage.userWorkspaceDirectory("user").resolve("memory/2026-10-29.md");
        Files.createDirectories(file.getParent());
        String original = "## Memory Flush — 2026-10-29\n用户对时代焦虑，接受了建议\n\n## 人工记录\n时代课上老师的转述\n";
        Files.writeString(file, original);
        var result = new LocalMemoryRecallAdapter(storage).recall(new MemoryRecallPort.Request(
                "user", "时代", 500, 4, null, () -> false));
        assertTrue(result.fragments().stream().anyMatch(f -> f.content().contains("老师的转述")));
        assertFalse(result.fragments().stream().anyMatch(f -> f.content().contains("焦虑")));
        assertEquals(original, Files.readString(file));
    }
}
