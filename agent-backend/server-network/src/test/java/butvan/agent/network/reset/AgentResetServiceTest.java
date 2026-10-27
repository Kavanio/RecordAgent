package butvan.agent.network.reset;

import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.reset.*;
import butvan.agent.agents.storage.AgentStorageProperties;
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
        return new AgentResetService(barrier, new AgentResetFiles(storage), new CurrentUserProvider(),
                new PendingApprovalStore(), new ActiveAgentRunRegistry(), List.of(participants), Clock.systemUTC());
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
        var barrier = new ResetBarrier(storage); barrier.markRuntimeUsed();
        var first = service(barrier, storage);
        first.confirm(confirmation(first.preview()));
        String id = waitFor(first, "WAITING_RESTART").resetId(); first.close();
        assertTrue(Files.exists(storage.getTranscriptDirectory().resolve("old.jsonl")));
        var resumed = service(new ResetBarrier(storage), storage);
        try { resumed.recover(); assertEquals(id, waitFor(resumed, "LOCAL_COMPLETE").resetId()); }
        finally { resumed.close(); }
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
