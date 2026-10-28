package butvan.agent.agents.reset;

import butvan.agent.agents.storage.AgentStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentResetFilesTest {
    @TempDir Path directory;
    @Test void deletesOnlyOwnedPersonalStateAndPreservesBusinessAndProjectSentinels() throws Exception {
        var storage = new AgentStorageProperties(directory.resolve("data-root"));
        Path root = storage.getRootDirectory();
        for (String name : new String[]{"sessions/catalog.json", "transcripts/old.jsonl", "runs/old.json",
                "agentscope/state/local-default/old/state.json", "agentscope/workspace/local-default/MEMORY.md",
                "agentscope/workspace/local-default/profile/history/old.md", "agentscope/workspace/local-default/agents/butvan_agent/tasks/old.json",
                "agentscope/workspace/bus/logs/old.json"}) {
            Path path = root.resolve(name); Files.createDirectories(path.getParent());
            Files.writeString(path, name.endsWith("catalog.json") ? "[]" : "old private memory");
        }
        String[] protectedFiles = {"config.json", "desktop/preferences.json", "projects/catalog.json",
                "files/objects/attachment", "data/butvan.db", "usage/system-usage.jsonl"};
        for (String name : protectedFiles) {
            Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, "sentinel");
        }
        Path project = directory.resolve("real-project/AGENTS.md");
        Files.createDirectories(project.getParent()); Files.writeString(project, "project sentinel");
        var cleaner = new AgentResetFiles(storage);
        cleaner.clean("local-default"); cleaner.clean("local-default");
        assertFalse(Files.exists(root.resolve("transcripts/old.jsonl")));
        assertFalse(Files.exists(root.resolve("agentscope/workspace/local-default/MEMORY.md")));
        assertFalse(Files.exists(root.resolve("agentscope/workspace/bus/logs/old.json")));
        for (String name : protectedFiles) assertEquals("sentinel", Files.readString(root.resolve(name)));
        assertEquals("project sentinel", Files.readString(project));
        assertTrue(Files.readString(storage.personalContextSettingsFile("local-default")).contains("\"enabled\":false"));
    }
    @Test void unknownManualWorkspaceFilesBlockResetWithoutErasingTheFileOrChat() throws Exception {
        var storage = new AgentStorageProperties(directory.resolve("data-root"));
        Path user = storage.userWorkspaceDirectory("local-default"); Files.createDirectories(user);
        Files.writeString(user.resolve("AGENTS.md"), "manual rules sentinel");
        Files.writeString(storage.getTranscriptDirectory().resolve("old.jsonl"), "old chat");
        assertThrows(ResetScopeException.class, () -> new AgentResetFiles(storage).clean("local-default"));
        assertEquals("manual rules sentinel", Files.readString(user.resolve("AGENTS.md")));
        assertEquals("old chat", Files.readString(storage.getTranscriptDirectory().resolve("old.jsonl")));
    }

    @Test void refusesSymlinkBeforeDeletingAnyPersonalFile() throws Exception {
        var storage = new AgentStorageProperties(directory.resolve("data-root"));
        Path outside = directory.resolve("project"); Files.createDirectories(outside);
        Files.writeString(outside.resolve("sentinel"), "keep");
        Path user = storage.userWorkspaceDirectory("local-default"); Files.createDirectories(user);
        Files.createSymbolicLink(user.resolve("memory"), outside);
        Files.writeString(storage.getTranscriptDirectory().resolve("old.jsonl"), "keep on failed preflight");
        assertThrows(java.io.IOException.class, () -> new AgentResetFiles(storage).clean("local-default"));
        assertTrue(Files.exists(storage.getTranscriptDirectory().resolve("old.jsonl")));
        assertEquals("keep", Files.readString(outside.resolve("sentinel")));
    }
}
