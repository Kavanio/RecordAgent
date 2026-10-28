package butvan.agent.network.memory;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import butvan.agent.network.controller.ApiExceptionHandler;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.controller.MemoryDocumentsController;
import butvan.agent.network.memory.dto.MemoryDtos.*;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.reset.MemoryResetParticipant;
import butvan.agent.network.memory.service.*;
import butvan.agent.network.reset.service.ResetOperationAspect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 临时数据库、真实 transcript 和假远端的管理闭环；不访问真实用户或网络。 */
class MemoryManagementIntegrationTest {
    @TempDir Path directory;

    private MemoryManagementService manager(MemorySyncFixture f) { return manager(f, f.base.service); }
    private MemoryManagementService manager(MemorySyncFixture f, MemoryService memory) {
        return new MemoryManagementService(memory, new MemoryManagementRepository(f.base.jdbc), new MemorySyncRepository(f.base.jdbc),
                new MemorySourceVerifier(f.transcript, f.sessions), f.base.remote, f.base.barrier, f.base.transactions, f.clock);
    }
    private String source(MemorySyncFixture f, boolean sync) {
        String turn = f.admit("合成经历：周末学习了一小时摄影");
        f.finish(turn, MessageStatus.COMPLETED, true);
        if (sync) f.sync.runOnce(1);
        return new MemoryManagementRepository(f.base.jdbc).documents(f.base.service.settings().spaceId(), 0, 50).getLast().id();
    }

    @Test void paginationOwnershipAndOriginalAreIndependentFromRemoteAvailability() {
        var f = new MemorySyncFixture(directory); f.base.enabled();
        var service = manager(f);
        String first = source(f, false); String second = source(f, false);
        var page = service.documents("0", 1);
        assertEquals(first, page.items().getFirst().id()); assertNotNull(page.nextCursor());
        assertEquals(second, service.documents(page.nextCursor(), 1).items().getFirst().id());
        assertNull(service.documents(page.nextCursor(), 1).nextCursor());
        assertEquals("AVAILABLE", service.document(first).originalStatus());
        assertTrue(service.document(first).originalText().contains("摄影"));
        assertEquals(400, assertThrows(MemoryRequestException.class, () -> service.documents("-1", 1)).getCode());
        assertThrows(MemoryRequestException.class, () -> service.documents("0", 51));
        var other = f.base.service(f.base.remote, new CurrentUserProvider() { public String currentUserId() { return "other"; } }, "test");
        assertTrue(manager(f, other).documents("0", 20).items().isEmpty());
        assertEquals(404, assertThrows(MemoryRequestException.class, () -> manager(f, other).document(first)).getCode());
        assertEquals(404, assertThrows(MemoryRequestException.class, () -> manager(f, other).delete(first, new DeleteDocument(0, true, "foreign"))).getCode());
        assertEquals(0, f.base.remote.deletions.get());
    }

    @Test void correctionIsLocalIdempotentVersionedAndDoesNotEditTranscriptOrUpload() {
        var f = new MemorySyncFixture(directory); f.base.enabled();
        String id = source(f, false);
        var service = manager(f);
        String before = readTranscript(f);
        var input = new Correct(0, "合成纠正：实际学习了两小时摄影", true, "correct-one");
        var task = service.correct(id, input);
        assertEquals(task, service.correct(id, input));
        assertEquals("SUCCEEDED", task.state());
        assertEquals("LOCAL_ONLY", service.document(id).correctionStatus());
        assertEquals("AVAILABLE", service.document(id).originalStatus());
        assertEquals(input.correctedText(), service.document(id).correctedText());
        assertEquals("REVOKED", service.document(id).document().state());
        assertEquals(before, readTranscript(f));
        f.sync.runOnce(20); assertTrue(f.writer.writes.isEmpty());
        assertThrows(MemoryRequestException.class, () -> service.correct(id, new Correct(1, "另一个纠正", true, "correct-one")));
        assertThrows(MemoryRequestException.class, () -> service.delete(id, new DeleteDocument(0, true, "stale")));
        assertThrows(MemoryRequestException.class, () -> service.correct(id, new Correct(1, "```java\nsecret\n```", true, "code")));
        assertThrows(MemoryRequestException.class, () -> service.delete(id, new DeleteDocument(1, false, "unconfirmed")));
        service.delete(id, new DeleteDocument(1, true, "delete-local"));
        assertNull(service.document(id).correctedText());
        assertEquals(before, readTranscript(f));
        assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM memory_correction", Integer.class));
        assertEquals(0, f.base.remote.deletions.get());
    }

    @Test void deletionRestoresTaskAfterReconstructionAndUnknownKeepsRecallBlockedUntilVerified() {
        var f = new MemorySyncFixture(directory); f.base.enabled();
        String id = source(f, true); var service = manager(f);
        String before = readTranscript(f);
        var input = new DeleteDocument(0, true, "delete-remote");
        var task = service.delete(id, input);
        assertEquals("PENDING", task.state()); assertEquals(0, f.base.remote.deletions.get());
        var recall = new MemoryRecallRepository(f.base.jdbc);
        assertTrue(recall.deletionPending(f.base.service.settings().spaceId()));
        assertTrue(recall.verifiedSource(f.base.service.settings().spaceId(), f.writer.writes.getFirst().target().documentId(), f.base.remote.fingerprint).isEmpty());
        assertEquals(task, manager(f).operation(task.id()));
        assertEquals(task, manager(f).delete(id, input));
        f.base.remote.result = MemoryRemoteGateway.DeletionResult.UNKNOWN;
        assertEquals("UNKNOWN", service.retry(task.id()).state());
        assertTrue(recall.deletionPending(f.base.service.settings().spaceId()));
        f.base.remote.beforeDeletion = () -> assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
        f.base.remote.result = MemoryRemoteGateway.DeletionResult.VERIFIED;
        assertEquals("SUCCEEDED", manager(f).retry(task.id()).state());
        assertFalse(recall.deletionPending(f.base.service.settings().spaceId()));
        manager(f).retry(task.id()); assertEquals(2, f.base.remote.deletions.get());
        assertEquals(before, readTranscript(f));
    }

    @Test void inFlightWriteMustFinishBeforeDeleteCanBeVerified() throws Exception {
        var f = new MemorySyncFixture(directory); f.base.enabled();
        String id = source(f, false); var service = manager(f);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        f.writer.beforeSubmit = () -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> f.sync.runOnce(1));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var task = service.delete(id, new DeleteDocument(0, true, "delete-inflight"));
                assertEquals("WAITING_REMOTE", service.retry(task.id()).state());
                assertEquals(0, f.base.remote.deletions.get());
                release.countDown(); future.get(5, TimeUnit.SECONDS);
                assertEquals("CANCELLED", f.sync.operations().getFirst().state());
                assertEquals("SUCCEEDED", service.retry(task.id()).state());
                assertEquals(1, f.base.remote.deletions.get());
            } finally { release.countDown(); }
        }
    }

    @Test void missingRecipientOrRemoteFailureCannotClaimDeletionComplete() {
        var f = new MemorySyncFixture(directory); f.base.enabled();
        var service = manager(f); String id = source(f, true);
        var task = service.delete(id, new DeleteDocument(0, true, "delete"));
        String recipient = f.base.remote.fingerprint; f.base.remote.fingerprint = "";
        assertEquals("UNKNOWN", service.retry(task.id()).state()); assertEquals(0, f.base.remote.deletions.get());
        f.base.remote.fingerprint = recipient; f.base.remote.result = MemoryRemoteGateway.DeletionResult.FAILED;
        assertEquals("FAILED", service.retry(task.id()).state());
        f.base.remote.beforeDeletion = () -> { throw new IllegalStateException("fake unavailable"); };
        assertEquals("UNKNOWN", service.retry(task.id()).state());
    }

    @Test void resetClearsCorrectionsAndTasksThroughSourceCascadeAndGuardsLateCommands() throws Exception {
        var f = new MemorySyncFixture(directory); f.base.enabled();
        var service = manager(f); String id = source(f, false);
        service.correct(id, new Correct(0, "合成纠正内容", true, "correct"));
        f.base.barrier.begin("reset", "reset-request", f.base.user.currentUserId(), "memory-m2-v2:fake");
        assertThrows(butvan.agent.agents.reset.ResetBlockedException.class, () -> service.delete(id, new DeleteDocument(1, true, "late")));
        var participant = new MemoryResetParticipant(new MemoryResetRepository(f.base.jdbc, f.base.repository),
                f.base.barrier, f.base.remote, f.base.transactions, "test");
        participant.stop(f.base.user.currentUserId()); participant.clean(f.base.user.currentUserId());
        assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM memory_correction", Integer.class));
        assertEquals(0, f.base.jdbc.queryForObject("SELECT COUNT(*) FROM memory_management_operation", Integer.class));
    }

    @Test void managementCorrectionInvalidatesAlreadyCachedContextBeforeTheNextModelCall() {
        try (var f = new MemoryRecallFixture(directory)) {
            f.prepared();
            var context = f.context(f.assembler.assemble(f.request("英语学习")));
            assertEquals(3, f.inject(context).size());
            var service = manager(f.sync);
            String id = service.documents("0", 20).items().getFirst().id();
            var task = service.correct(id, new Correct(0, "合成纠正：这次尝试没有继续", true, "correct-cached"));
            assertEquals(2, f.inject(context).size());
            service.retry(task.id());
            assertEquals(2, f.inject(context).size());
            assertTrue(f.assembler.assemble(f.request("英语学习")).isEmpty());
            assertEquals(2, f.gateway.calls.get());
        }
    }

    @Test void httpDeleteBodyRequiresGenerationAndRefreshReadsOriginalTask() throws Exception {
        var f = new MemorySyncFixture(directory); f.base.enabled(); String id = source(f, false);
        var proxy = new AspectJProxyFactory(manager(f)); proxy.setProxyTargetClass(true); proxy.addAspect(new ResetOperationAspect(f.base.barrier));
        var mvc = MockMvcBuilders.standaloneSetup(new MemoryDocumentsController(proxy.getProxy())).setControllerAdvice(new ApiExceptionHandler()).build();
        String body = "{\"expectedVersion\":0,\"confirmed\":true,\"requestId\":\"http-delete\"}";
        mvc.perform(delete("/agent/memory/documents/" + id).contentType("application/json").content(body)).andExpect(jsonPath("$.code").value(428));
        for (int i = 0; i < 2; i++) mvc.perform(delete("/agent/memory/documents/" + id).header("X-Agent-Generation", "0").contentType("application/json").content(body))
                .andExpect(jsonPath("$.data.state").value("SUCCEEDED")).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/agent/memory/documents/" + id)).andExpect(jsonPath("$.data.originalStatus").value("AVAILABLE"));
        mvc.perform(get("/agent/memory/documents").param("limit", "100")).andExpect(jsonPath("$.code").value(400));
        f.base.barrier.begin("reset", "request", f.base.user.currentUserId()); f.base.barrier.complete();
        mvc.perform(post("/agent/memory/operations/unknown/retry").header("X-Agent-Generation", "0")).andExpect(jsonPath("$.code").value(423));
    }

    private String readTranscript(MemorySyncFixture f) {
        try { return Files.readString(f.base.storage.getTranscriptDirectory().resolve(f.session + ".jsonl")); }
        catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
}
