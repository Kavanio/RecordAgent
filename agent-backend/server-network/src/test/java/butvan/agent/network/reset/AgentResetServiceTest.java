package butvan.agent.network.reset;

import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.reset.*;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.agents.subagent.AgentDefinitionLoader;
import butvan.agent.agents.subagent.SubagentCatalog;
import butvan.agent.network.reset.dto.ResetDtos.*;
import butvan.agent.network.reset.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AgentResetServiceTest {
    @TempDir Path directory;
    AgentResetService service(ResetBarrier barrier, AgentStorageProperties storage, ResetParticipant... participants) {
        return service(barrier, storage, new SubagentCatalog(new AgentDefinitionLoader(), storage), participants);
    }
    AgentResetService service(ResetBarrier barrier, AgentStorageProperties storage, SubagentCatalog catalog,
                              ResetParticipant... participants) {
        return new AgentResetService(barrier, new AgentResetFiles(storage), new CurrentUserProvider(),
                new PendingApprovalStore(), new ActiveAgentRunRegistry(), catalog, List.of(participants), Clock.systemUTC());
    }
    Path oldSubagent(AgentStorageProperties storage, String name) throws Exception {
        Path file = storage.getWorkspaceDirectory().resolve("agents/" + name + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "---\nname: " + name + "\ndescription: 旧的个人定义\n---\n旧个人上下文哨兵\n");
        return file;
    }
    Confirm confirmation(Preview preview) {
        return new Confirm(preview.confirmationToken(), AgentResetService.CONFIRMATION, UUID.randomUUID().toString(), preview.scopeVersion());
    }
    Status waitFor(AgentResetService service, String state) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!service.current().status().equals(state) && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(state, service.current().status());
        return service.current();
    }
    @Test void previewAndWrongConfirmationCannotDeleteAndDuplicateRequestReusesTask() throws Exception {
        var storage = new AgentStorageProperties(directory);
        Files.writeString(storage.getTranscriptDirectory().resolve("old.jsonl"), "private");
        var barrier = new ResetBarrier(storage);
        var service = service(barrier, storage);
        try {
            var preview = service.preview();
            assertTrue(Files.exists(storage.getTranscriptDirectory().resolve("old.jsonl")));
            assertThrows(ResetRequestException.class, () -> service.confirm(new Confirm(preview.confirmationToken(), "yes", "id", preview.scopeVersion())));
            assertFalse(barrier.blocked());
            var confirm = confirmation(preview);
            String id = service.confirm(confirm).resetId();
            assertEquals(id, service.confirm(confirm).resetId());
            var result = waitFor(service, "LOCAL_COMPLETE");
            assertFalse(Files.exists(storage.getTranscriptDirectory().resolve("old.jsonl")));
            assertEquals("NOT_INTEGRATED", result.remoteStatus());
            assertThrows(ResetRequestException.class, () -> service.confirm(confirmation(preview)));
        } finally { service.close(); }
    }
    @Test void runtimeRequiresRestartBeforeAnyDestructionAndJournalResumesAfterRestart() throws Exception {
        var storage = new AgentStorageProperties(directory);
        Files.writeString(storage.getTranscriptDirectory().resolve("old.jsonl"), "private");
        Path definition = oldSubagent(storage, "old-helper");
        var barrier = new ResetBarrier(storage); barrier.markRuntimeUsed();
        var first = service(barrier, storage);
        first.confirm(confirmation(first.preview()));
        String id = waitFor(first, "WAITING_RESTART").resetId(); first.close();
        assertTrue(Files.exists(storage.getTranscriptDirectory().resolve("old.jsonl")));
        // 模拟重启：Spring 单例先从旧文件加载定义，随后才续做重置。
        var catalog = new SubagentCatalog(new AgentDefinitionLoader(), storage);
        assertNotNull(catalog.get("old-helper"));
        var resumed = service(new ResetBarrier(storage), storage, catalog);
        try {
            resumed.recover();
            assertEquals(id, waitFor(resumed, "LOCAL_COMPLETE").resetId());
            assertFalse(Files.exists(definition));
            assertNull(catalog.get("old-helper"));
            assertEquals(Set.of("general-purpose", "explore", "plan"), Set.copyOf(catalog.names()));
        } finally { resumed.close(); }
    }
    @Test void resetInvalidatesCachedDefinitionsAndRestoresOverriddenBuiltinBeforeCompleting() throws Exception {
        var storage = new AgentStorageProperties(directory);
        Path custom = oldSubagent(storage, "old-helper");
        oldSubagent(storage, "general-purpose");
        var catalog = new SubagentCatalog(new AgentDefinitionLoader(), storage);
        var oldBuiltin = catalog.get("general-purpose");
        assertNotNull(catalog.get("old-helper"));
        var service = service(new ResetBarrier(storage), storage, catalog);
        try {
            service.confirm(confirmation(service.preview()));
            waitFor(service, "LOCAL_COMPLETE");
            assertFalse(Files.exists(custom));
            assertNull(catalog.get("old-helper"));
            assertNotSame(oldBuiltin, catalog.get("general-purpose"));
            assertEquals(new SubagentCatalog(new AgentDefinitionLoader(), storage).names(), catalog.names());
        } finally { service.close(); }
    }
    @Test void cacheResetFailureKeepsBarrierAndRetryInvalidatesCacheEvenAfterFilesWereDeleted() throws Exception {
        var storage = new AgentStorageProperties(directory);
        Path definition = oldSubagent(storage, "old-helper");
        var fail = new AtomicBoolean(true);
        var catalog = new SubagentCatalog(new AgentDefinitionLoader(), storage) {
            @Override public synchronized void resetToBuiltins() {
                if (fail.get()) throw new IllegalStateException("模拟缓存失效失败");
                super.resetToBuiltins();
            }
        };
        var barrier = new ResetBarrier(storage);
        var service = service(barrier, storage, catalog);
        try {
            service.confirm(confirmation(service.preview()));
            String id = waitFor(service, "FAILED").resetId();
            assertFalse(Files.exists(definition));
            assertTrue(service.current().completedSteps().contains("personal-files"));
            assertFalse(service.current().completedSteps().contains("memory-caches"));
            assertThrows(ResetBlockedException.class, barrier::enter);
            fail.set(false);
            service.retry(id, new Retry(true));
            waitFor(service, "LOCAL_COMPLETE");
            assertNull(catalog.get("old-helper"));
        } finally { service.close(); }
    }
    @Test void failedParticipantKeepsBarrierAndRetryDoesNotResurrectOrDeleteProtectedFiles() throws Exception {
        var storage = new AgentStorageProperties(directory);
        Files.writeString(storage.getRootDirectory().resolve("config.json"), "credentials sentinel");
        var fail = new AtomicBoolean(true);
        ResetParticipant participant = new ResetParticipant() {
            public String id() { return "fake"; }
            public void clean(String owner) { if (fail.get()) throw new IllegalStateException("fake failure"); }
        };
        var barrier = new ResetBarrier(storage);
        var service = service(barrier, storage, participant);
        try {
            service.confirm(confirmation(service.preview()));
            String id = waitFor(service, "FAILED").resetId();
            assertThrows(ResetBlockedException.class, barrier::enter);
            fail.set(false); service.retry(id, new Retry(true)); waitFor(service, "LOCAL_COMPLETE");
            assertEquals("credentials sentinel", Files.readString(storage.getRootDirectory().resolve("config.json")));
        } finally { service.close(); }
    }
}
