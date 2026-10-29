package butvan.agent.network.memory;

import butvan.agent.agents.agent.permission.PendingApprovalStore;
import butvan.agent.agents.agent.run.ActiveAgentRunRegistry;
import butvan.agent.agents.reset.*;
import butvan.agent.agents.subagent.*;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.repository.MemoryResetRepository;
import butvan.agent.network.memory.reset.MemoryResetParticipant;
import butvan.agent.network.memory.service.MemoryService;
import butvan.agent.network.reset.dto.ResetDtos.*;
import butvan.agent.network.reset.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 经真实重置协调器验证未知结果、并发、重启和业务保护；远端仅为假 Adapter。 */
class MemoryResetIntegrationTest {
    @TempDir Path directory;

    private AgentResetService reset(MemoryFixture f, ResetBarrier barrier) {
        var participant = new MemoryResetParticipant(new MemoryResetRepository(f.jdbc, f.repository), barrier,
                f.remote, f.transactions, "test");
        return new AgentResetService(barrier, new AgentResetFiles(f.storage), f.user,
                new PendingApprovalStore(), new ActiveAgentRunRegistry(),
                new SubagentCatalog(new AgentDefinitionLoader(), f.storage), List.of(participant), f.clock);
    }
    private Confirm confirmation(Preview preview) {
        return new Confirm(preview.confirmationToken(), AgentResetService.CONFIRMATION,
                UUID.randomUUID().toString(), preview.scopeVersion());
    }
    private Status waitFor(AgentResetService service, String expected) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!service.current().status().equals(expected) && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(expected, service.current().status());
        return service.current();
    }
    private void target(MemoryFixture f) {
        f.enabled();
        var receipt = f.service.capture(f.source()).orElseThrow();
        f.service.recordRemoteOperation(receipt, "stable-operation");
    }

    @Test void unknownRemoteResultKeepsBarrierAndRestartReusesDeletionTargetAndResetId() throws Exception {
        var f = new MemoryFixture(directory);
        target(f);
        Files.writeString(f.storage.getTranscriptDirectory().resolve("old.jsonl"), "old user text");
        Files.writeString(directory.resolve("config.json"), "protected credentials");
        Files.createDirectories(directory.resolve("data/files"));
        Files.writeString(directory.resolve("data/files/attachment"), "protected business attachment");
        f.remote.result = MemoryRemoteGateway.DeletionResult.UNKNOWN;
        var first = reset(f, f.barrier);
        String id;
        try {
            var preview = first.preview();
            assertTrue(preview.deletes().stream().anyMatch(s -> s.contains("经历记忆")));
            first.confirm(confirmation(preview));
            var failed = waitFor(first, "FAILED");
            id = failed.resetId();
            assertEquals("UNKNOWN", failed.remoteStatus());
            assertTrue(f.barrier.blocked());
            assertTrue(Files.exists(f.storage.getTranscriptDirectory().resolve("old.jsonl")));
        } finally { first.close(); }

        f.remote.result = MemoryRemoteGateway.DeletionResult.VERIFIED;
        var resumedBarrier = new ResetBarrier(f.storage);
        var resumed = reset(f, resumedBarrier);
        try {
            resumed.recover();
            var complete = waitFor(resumed, "LOCAL_COMPLETE");
            assertEquals(id, complete.resetId());
            assertEquals("COMPLETE", complete.remoteStatus());
            assertEquals(2, f.remote.deletions.get());
            assertFalse(Files.exists(f.storage.getTranscriptDirectory().resolve("old.jsonl")));
            assertEquals("protected credentials", Files.readString(directory.resolve("config.json")));
            assertEquals("protected business attachment", Files.readString(directory.resolve("data/files/attachment")));
            var restored = new MemoryService(f.repository, resumedBarrier, f.user, f.clock, f.remote, f.transactions, "test");
            assertFalse(restored.grant().active());
            assertFalse(restored.settings().autoSave());
            assertFalse(restored.settings().autoRecall());
            assertTrue(restored.capture(new butvan.agent.agents.memory.MemorySourceRegistry.Source(f.user.currentUserId(),
                    "new-session", "new-turn", "new-message", f.clock.instant(),
                    butvan.agent.agents.memory.MemorySourceRegistry.Channel.DESKTOP, "a".repeat(64), 1)).isEmpty());
        } finally { resumed.close(); }
    }

    @Test void missingRecipientCannotSkipPreviouslyRecordedTargetsAndRetryCanFinish() throws Exception {
        var f = new MemoryFixture(directory);
        target(f);
        String recipient = f.remote.fingerprint;
        f.remote.fingerprint = "";
        var reset = reset(f, f.barrier);
        try {
            reset.confirm(confirmation(reset.preview()));
            var failed = waitFor(reset, "FAILED");
            assertEquals("FAILED", failed.remoteStatus());
            assertEquals(0, f.remote.deletions.get());
            f.remote.fingerprint = recipient;
            reset.retry(failed.resetId(), new Retry(true));
            assertEquals("COMPLETE", waitFor(reset, "LOCAL_COMPLETE").remoteStatus());
            assertEquals(1, f.remote.deletions.get());
        } finally { reset.close(); }
    }

    @Test void resetRejectsLateSourceAndOperationWhileRemoteCleanupIsInFlight() throws Exception {
        var f = new MemoryFixture(directory);
        f.enabled();
        var source = f.source();
        var receipt = f.service.capture(source).orElseThrow();
        f.service.recordRemoteOperation(receipt, "in-flight");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        f.remote.beforeDeletion = () -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fake timeout"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("fake interrupted"); }
        };
        var reset = reset(f, f.barrier);
        try {
            reset.confirm(confirmation(reset.preview()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(f.service.isCurrent(receipt));
            assertThrows(ResetBlockedException.class, () -> f.service.capture(source));
            assertThrows(ResetBlockedException.class, () -> f.service.recordRemoteOperation(receipt, "late"));
            release.countDown();
            waitFor(reset, "LOCAL_COMPLETE");
            assertFalse(f.service.isCurrent(receipt));
            assertThrows(ResetBlockedException.class, () -> f.service.capture(source));
        } finally { release.countDown(); reset.close(); }
    }

    @Test void recipientChangeInvalidatesPreviewAndOldTicketCannotUseAnotherPreviewScope() {
        var f = new MemoryFixture(directory);
        var reset = reset(f, f.barrier);
        try {
            var old = reset.preview();
            f.remote.fingerprint = "new-recipient";
            assertEquals(409, assertThrows(ResetRequestException.class, () -> reset.confirm(confirmation(old))).code());
            var fresh = reset.preview();
            var mixed = new Confirm(old.confirmationToken(), AgentResetService.CONFIRMATION, "mixed", fresh.scopeVersion());
            assertEquals(409, assertThrows(ResetRequestException.class, () -> reset.confirm(mixed)).code());
            assertFalse(f.barrier.blocked());
        } finally { reset.close(); }
    }

    @Test void neverIntegratedHasNoRemoteCallsAndOldM1CompletionIsNotReclassified() throws Exception {
        var f = new MemoryFixture(directory);
        f.remote.fingerprint = "";
        var reset = reset(f, f.barrier);
        try {
            assertEquals("NOT_INTEGRATED", reset.current().remoteStatus());
            reset.confirm(confirmation(reset.preview()));
            assertEquals("NOT_INTEGRATED", waitFor(reset, "LOCAL_COMPLETE").remoteStatus());
            assertEquals(0, f.remote.deletions.get());
        } finally { reset.close(); }
    }

    @Test void retryDoesNotDeleteVerifiedTargetsAgainAndRemoteCallHoldsNoWriteTransaction() throws Exception {
        var f = new MemoryFixture(directory);
        target(f);
        f.service.recordRemoteOperation(f.service.capture(f.source()).orElseThrow(), "second-operation");
        f.remote.beforeDeletion = () -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            f.remote.result = f.remote.deletions.get() == 2 ? MemoryRemoteGateway.DeletionResult.UNKNOWN
                    : MemoryRemoteGateway.DeletionResult.VERIFIED;
        };
        var reset = reset(f, f.barrier);
        try {
            reset.confirm(confirmation(reset.preview()));
            var failed = waitFor(reset, "FAILED");
            assertEquals(2, f.remote.deletions.get());
            reset.retry(failed.resetId(), new Retry(true));
            assertEquals("COMPLETE", waitFor(reset, "LOCAL_COMPLETE").remoteStatus());
            assertEquals(3, f.remote.deletions.get());
        } finally { reset.close(); }
    }

    @Test void legacyResetDoesNotGainRemoteDeletionPermissionDuringUpgrade() throws Exception {
        var f = new MemoryFixture(directory);
        target(f);
        f.barrier.begin("legacy-reset", "legacy-confirmation", f.user.currentUserId());
        // 模拟没有 scopeVersion 的旧 M1 持久化日志。
        var restored = new ResetBarrier(f.storage);
        assertNull(restored.state().scopeVersion());
        var reset = reset(f, restored);
        try {
            reset.recover();
            assertEquals("UNKNOWN", waitFor(reset, "FAILED").remoteStatus());
            assertEquals(0, f.remote.deletions.get());
        } finally { reset.close(); }
    }
}
