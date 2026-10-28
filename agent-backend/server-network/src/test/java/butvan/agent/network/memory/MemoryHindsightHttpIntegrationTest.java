package butvan.agent.network.memory;

import butvan.agent.agents.memory.MemorySourceRegistry.Channel;
import butvan.agent.network.memory.client.*;
import butvan.agent.network.memory.repository.*;
import butvan.agent.network.memory.service.MemorySyncService;
import butvan.agent.agents.session.dto.TranscriptMessageDto.MessageStatus;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 SQLite／调用链与本机随机端口假 Hindsight；绝不连接实际部署或模型。 */
class MemoryHindsightHttpIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> deletes = new CopyOnWriteArrayList<>();
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger posts = new AtomicInteger();
    private final List<HindsightHttpAdapter> clients = new ArrayList<>();
    private HttpServer server;
    private ExecutorService threads;
    private MemoryFixture fixture;
    private MemoryRemoteOperationRepository journal;
    private HindsightHttpAdapter client;
    private String parentId, documentId, bankId;
    private final String childId = UUID.randomUUID().toString();
    private final String secondChild = UUID.randomUUID().toString();
    private volatile String parentState = "completed", childState = "completed";
    private volatile boolean twoChildren, hideFirstChild, documentGone, factsRemain, loseSubmitAck, loseChildAck, loseDocumentAck, invalidAck, failParentDelete;
    private volatile JsonNode recall;
    private volatile Runnable beforeRecall = () -> { };
    private volatile JsonNode lastWrite, lastRecall;

    @BeforeEach void setup() throws IOException {
        fixture = new MemoryFixture(directory);
        journal = new MemoryRemoteOperationRepository(fixture.jdbc, fixture.transactions);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(threads);
        server.createContext("/gateway/", this::handle); server.start();
        client = adapter(journal, "synthetic-chain");
        fixture.remote.fingerprint = client.recipientFingerprint(); fixture.enabled();
    }
    @AfterEach void teardown() { clients.forEach(HindsightHttpAdapter::close); server.stop(0); threads.shutdownNow(); }
    private URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/gateway/"); }
    private HindsightHttpAdapter adapter(MemoryRemoteOperationRepository repository, String chain) {
        var adapter = new HindsightHttpAdapter(endpoint(), "", chain, repository, mapper); clients.add(adapter); return adapter;
    }
    private MemoryWriteGateway.Write write() {
        var receipt = fixture.service.capture(fixture.source()).orElseThrow();
        String id = UUID.randomUUID().toString(); fixture.service.recordRemoteOperation(receipt, id);
        var source = new MemorySyncRepository(fixture.jdbc).source(receipt.sourceId()).orElseThrow();
        var target = new MemoryRemoteGateway.Target(source.space(), source.document(), client.recipientFingerprint(), id);
        return new MemoryWriteGateway.Write(id, target, "纯虚构经历：周末练习了摄影", fixture.clock.instant(), Channel.DESKTOP);
    }

    @Test void asyncWriteUsesStableIdentityAndOriginalQueryWithoutRepostingContent() {
        var write = write();
        assertEquals(MemoryWriteGateway.Outcome.ACCEPTED, client.submit(write, () -> false));
        assertEquals(write.operationId(), lastWrite.path("operation_id").asText()); assertTrue(lastWrite.path("async").asBoolean());
        var item = lastWrite.path("items").get(0);
        assertEquals("replace", item.path("update_mode").asText()); assertEquals(write.content(), item.path("content").asText());
        assertEquals(write.occurredAt().toString(), item.path("timestamp").asText());
        assertEquals("user", item.path("metadata").path("role").asText());
        assertEquals(MemoryWriteGateway.Outcome.SUCCEEDED, client.submit(write, () -> false));
        assertEquals(1, posts.get());
        assertTrue(journal.operation(write.target()).orElseThrow().terminalSeen());
        assertEquals(1, journal.children(write.operationId()).size());
        assertTrue(calls.stream().filter(c -> c.contains("operations/")).allMatch(c -> c.endsWith("include_payload=false")));
        assertFalse(journal.operation(write.target()).orElseThrow().toString().contains("摄影"));
        var changed = new MemoryWriteGateway.Write(write.operationId(), write.target(), "改动后的另一份文本", write.occurredAt(), Channel.DESKTOP);
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.submit(changed, () -> false)); assertEquals(1, posts.get());
    }

    @Test void unknownAcknowledgementAndReconstructedAdapterOnlyQueryOriginalOperation() {
        loseSubmitAck = true; var write = write();
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.submit(write, () -> false));
        var restarted = adapter(new MemoryRemoteOperationRepository(fixture.jdbc, fixture.transactions), "synthetic-chain");
        assertEquals(MemoryWriteGateway.Outcome.SUCCEEDED, restarted.submit(write, () -> false));
        assertEquals(1, posts.get());
    }

    @Test void invalidAcknowledgementAndForeignTargetsCannotPublishOrReadAnotherOperation() {
        var write = write(); invalidAck = true;
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.submit(write, () -> false));
        int before = calls.size();
        var foreign = new MemoryRemoteGateway.Target("foreign-space", write.target().documentId(), client.recipientFingerprint(), write.operationId());
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.query(write.operationId(), foreign, () -> false));
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, client.deleteAndVerify(foreign)); assertEquals(before, calls.size());
        var another = adapter(journal, "another-model-chain");
        assertNotEquals(client.recipientFingerprint(), another.recipientFingerprint());
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, another.query(write.operationId(), write.target(), () -> false));
    }

    @Test void cancelledMissingOrDisappearingChildrenNeverProveSuccessfulWriteOrSafeCleanup() {
        var write = write(); client.submit(write, () -> false); twoChildren = true; parentState = "processing";
        assertEquals(MemoryWriteGateway.Outcome.ACCEPTED, client.query(write.operationId(), write.target(), () -> false));
        parentState = "completed";
        assertEquals(MemoryWriteGateway.Outcome.SUCCEEDED, client.query(write.operationId(), write.target(), () -> false));
        hideFirstChild = true; parentState = "completed";
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.query(write.operationId(), write.target(), () -> false));
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, client.deleteAndVerify(write.target())); assertTrue(deletes.isEmpty());
        hideFirstChild = false; childState = "cancelled";
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.query(write.operationId(), write.target(), () -> false));
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, client.deleteAndVerify(write.target())); assertTrue(deletes.isEmpty());
        parentState = "not_found";
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.query(write.operationId(), write.target(), () -> false));
    }

    @Test void cleanupRemovesKnownDocumentThenChildrenThenParentButNeverClaimsCompleteDerivedErasure() {
        var write = write(); client.submit(write, () -> false); twoChildren = true;
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, client.deleteAndVerify(write.target()));
        assertEquals("document", deletes.getFirst()); assertEquals(write.operationId(), deletes.getLast());
        assertTrue(deletes.contains(childId)); assertTrue(deletes.contains(secondChild));
        var progress = journal.operation(write.target()).orElseThrow();
        assertTrue(progress.documentDeleted()); assertTrue(progress.factsChecked()); assertTrue(progress.parentDeleted());
        assertTrue(journal.children(write.operationId()).stream().allMatch(MemoryRemoteOperationRepository.Child::deleted));
        int before = calls.size();
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, adapter(journal, "synthetic-chain").deleteAndVerify(write.target()));
        assertEquals(before, calls.size());
    }

    @Test void unseenChildMakesDeletionUnknownEvenWhenAllVisibleTargetsAreGone() {
        var write = write(); client.submit(write, () -> false); twoChildren = true; hideFirstChild = true;
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, client.deleteAndVerify(write.target()));
        assertTrue(documentGone); assertTrue(removed.contains(secondChild)); assertTrue(removed.contains(write.operationId()));
        assertFalse(removed.contains(childId), "本机服务刻意隐藏一个未观测任务，父完成不能证明不存在残留载荷");
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, adapter(journal, "synthetic-chain").deleteAndVerify(write.target()));
        assertEquals(1, posts.get());
    }

    @Test void emptyChildrenDuringPartialCleanupNeverPublishSuccessfulWriteAndCanStillResumeCleanup() {
        var write = write(); client.submit(write, () -> false); failParentDelete = true;
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, client.deleteAndVerify(write.target()));
        assertTrue(removed.contains(childId)); assertFalse(removed.contains(write.operationId()));
        assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.query(write.operationId(), write.target(), () -> false),
                "允许续做已发起删除的历史，不等于空子清单可以发布写入成功");

        failParentDelete = false;
        var restarted = adapter(new MemoryRemoteOperationRepository(fixture.jdbc, fixture.transactions), "synthetic-chain");
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, restarted.deleteAndVerify(write.target()));
        assertTrue(removed.contains(write.operationId()));
        assertEquals(1, deletes.stream().filter(childId::equals).count()); assertEquals(1, posts.get());
    }

    @Test void cleanupWithRemainingFactsKeepsTaskPayloadPointersAndResumesAfterRestart() {
        var write = write(); client.submit(write, () -> false); factsRemain = true;
        client.deleteAndVerify(write.target()); assertEquals(List.of("document"), deletes);
        assertFalse(journal.operation(write.target()).orElseThrow().factsChecked());
        factsRemain = false;
        var restarted = adapter(new MemoryRemoteOperationRepository(fixture.jdbc, fixture.transactions), "synthetic-chain");
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, restarted.deleteAndVerify(write.target()));
        assertEquals(1, deletes.stream().filter("document"::equals).count()); assertTrue(removed.contains(write.operationId()));
    }

    @Test void reappearingDocumentWithoutFactsMustNotDiscardRemoteTaskEvidenceOnResume() {
        var write = write(); client.submit(write, () -> false); factsRemain = true;
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, client.deleteAndVerify(write.target()));
        assertEquals(List.of("document"), deletes);

        // 模拟旧清理步骤已落盘后，迟到写入重建了文档，但尚未生成可列举的事实。
        factsRemain = false; documentGone = false;
        var restarted = adapter(new MemoryRemoteOperationRepository(fixture.jdbc, fixture.transactions), "synthetic-chain");
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, restarted.deleteAndVerify(write.target()));
        assertEquals(List.of("document"), deletes, "直接事实为空不能掩盖文档重现并继续删除任务载荷");
        assertFalse(removed.contains(childId)); assertFalse(removed.contains(write.operationId()));
        assertEquals(1, posts.get(), "核对清理不能重放原始写入");
    }

    @Test void resumedCleanupMustRecheckCurrentOperationsInsteadOfUsingOldTerminalFlags() {
        var write = write(); client.submit(write, () -> false); factsRemain = true;
        client.deleteAndVerify(write.target()); assertEquals(List.of("document"), deletes);

        factsRemain = false; parentState = "processing"; childState = "processing";
        var restarted = adapter(new MemoryRemoteOperationRepository(fixture.jdbc, fixture.transactions), "synthetic-chain");
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, restarted.deleteAndVerify(write.target()));
        assertEquals(List.of("document"), deletes, "已完成的文档步骤不能代替当前任务停稳核对");
        assertFalse(removed.contains(childId)); assertFalse(removed.contains(write.operationId()));
    }

    @Test void lostDocumentAndChildDeleteAcknowledgementsRecoverUsingPersistedIntent() {
        var write = write(); client.submit(write, () -> false); loseDocumentAck = true;
        client.deleteAndVerify(write.target()); assertTrue(documentGone); assertFalse(journal.operation(write.target()).orElseThrow().documentDeleted());
        loseDocumentAck = false; loseChildAck = true;
        client.deleteAndVerify(write.target()); assertTrue(removed.contains(childId)); assertFalse(journal.children(write.operationId()).getFirst().deleted());
        loseChildAck = false;
        assertEquals(MemoryRemoteGateway.DeletionResult.UNKNOWN, adapter(journal, "synthetic-chain").deleteAndVerify(write.target()));
        assertEquals(1, deletes.stream().filter(childId::equals).count()); assertTrue(removed.contains(write.operationId()));
    }

    @Test void recallUsesRemainingBudgetAndOnlyDirectProvenanceWithoutObservationsOrDuplicates() {
        var space = fixture.service.settings().spaceId();
        recall = mapper.createObjectNode().set("results", mapper.createArrayNode()
                .add(mapper.createObjectNode().put("type", "observation").put("text", "未经完整追溯的推断").put("document_id", "doc-a"))
                .add(mapper.createObjectNode().put("type", "world").put("text", "纯虚构事实").put("document_id", "doc-a"))
                .add(mapper.createObjectNode().put("type", "world").put("text", "纯虚构事实").put("document_id", "doc-a"))
                .add(mapper.createObjectNode().put("type", "experience").put("text", "纯虚构体验").put("document_id", "doc-b")));
        var control = new MemoryRecallGateway.Control(System.nanoTime() + Duration.ofSeconds(1).toNanos(), () -> false);
        var result = client.recall(new MemoryRecallGateway.Query(space, "摄影学习", 5000, 20), control);
        assertEquals(2, result.size()); assertTrue(result.stream().allMatch(MemoryRecallGateway.Candidate::completeProvenance));
        assertEquals(List.of("doc-a"), result.getFirst().documentIds());
        assertEquals(500, lastRecall.path("max_tokens").asInt()); assertEquals("low", lastRecall.path("budget").asText());
        assertEquals(List.of("world", "experience"), mapper.convertValue(lastRecall.path("types"), List.class));
    }

    @Test void cancellationDeadlineAndUnsafeConnectionDoNotSendOrPersistNewRequests() {
        var write = write(); assertEquals(MemoryWriteGateway.Outcome.UNKNOWN, client.submit(write, () -> true)); assertEquals(0, posts.get());
        assertTrue(journal.operation(write.target()).isEmpty());
        var control = new MemoryRecallGateway.Control(System.nanoTime() - 1, () -> false);
        assertTrue(client.recall(new MemoryRecallGateway.Query(write.target().spaceId(), "摄影", 500, 4), control).isEmpty());
        assertTrue(calls.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HindsightHttpAdapter(URI.create("http://remote.example.invalid"), "", "chain", journal, mapper));
        assertThrows(IllegalArgumentException.class, () -> new HindsightHttpAdapter(endpoint(), "secret\r\n", "chain", journal, mapper));
    }

    @Test void actualWorkerKeepsAcceptedWriteSubmittedUntilTheOriginalHttpOperationCompletes() {
        var sync = new MemorySyncFixture(directory.resolve("worker"));
        var gateway = adapter(new MemoryRemoteOperationRepository(sync.base.jdbc, sync.base.transactions), "synthetic-chain");
        sync.base.remote.fingerprint = gateway.recipientFingerprint(); sync.base.enabled();
        String turn = sync.admit("纯虚构经历：尝试通过摄影学习英语"); sync.finish(turn, MessageStatus.COMPLETED, true);
        var worker = new MemorySyncService(sync.base.service, new MemorySyncRepository(sync.base.jdbc), sync.transcript, sync.sessions,
                sync.base.user, sync.base.barrier, gateway, sync.base.transactions, sync.clock, "test");
        worker.runOnce(1); assertEquals("SUBMITTED", worker.operations().getFirst().state());
        sync.clock.advance(Duration.ofSeconds(3)); worker.runOnce(1);
        assertEquals("SUCCEEDED", worker.operations().getFirst().state()); assertEquals(1, posts.get());
        assertFalse(sync.base.barrier.blocked());
    }

    @Test void resetCannotDiscardTheRemoteJournalWhileDerivedCleanupRemainsUnknown() {
        var write = write(); client.submit(write, () -> false);
        String owner = fixture.user.currentUserId();
        fixture.barrier.begin("reset", "request", owner, "memory-m2-v3:" + client.recipientFingerprint());
        var participant = new butvan.agent.network.memory.reset.MemoryResetParticipant(
                new MemoryResetRepository(fixture.jdbc, fixture.repository), fixture.barrier, client, fixture.transactions, "test");
        participant.stop(owner);
        assertThrows(IllegalStateException.class, () -> participant.clean(owner));
        assertTrue(fixture.barrier.blocked());
        assertEquals("UNKNOWN", participant.remoteStatus(owner, "reset"));
        assertTrue(journal.operation(write.target()).isPresent());
        assertTrue(journal.operation(write.target()).orElseThrow().parentDeleted());
        assertFalse(journal.children(write.operationId()).isEmpty());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath(), query = exchange.getRequestURI().getRawQuery();
        String method = exchange.getRequestMethod(); calls.add(method + " " + path + (query == null ? "" : "?" + query));
        String local = path.substring(path.indexOf("/banks/") + 7); bankId = local.substring(0, local.indexOf('/'));
        String suffix = local.substring(local.indexOf('/') + 1);
        JsonNode body; int status = 200;
        if (method.equals("POST") && suffix.equals("memories")) {
            posts.incrementAndGet(); lastWrite = mapper.readTree(exchange.getRequestBody()); parentId = lastWrite.path("operation_id").asText();
            documentId = lastWrite.path("items").get(0).path("document_id").asText();
            body = mapper.createObjectNode().put("success", true).put("async", true).put("bank_id", bankId).put("items_count", 1)
                    .put("operation_id", invalidAck ? UUID.randomUUID().toString() : parentId);
            if (loseSubmitAck) status = 503;
        } else if (suffix.equals("memories/recall")) {
            lastRecall = mapper.readTree(exchange.getRequestBody()); beforeRecall.run(); body = recall == null ? mapper.createObjectNode().putArray("results") : recall;
        } else if (suffix.startsWith("documents/")) {
            if (method.equals("DELETE")) { deletes.add("document"); documentGone = true; status = loseDocumentAck ? 503 : 200;
                body = mapper.createObjectNode().put("success", true).put("document_id", documentId);
            } else { status = documentGone ? 404 : 200; body = mapper.createObjectNode().put("id", documentId).put("bank_id", bankId); }
        } else if (suffix.startsWith("memories/list")) {
            var value = mapper.createObjectNode().put("total", factsRemain ? 1 : 0); var items = value.putArray("items");
            if (factsRemain) items.addObject().put("id", "remaining"); body = value;
        } else if (suffix.startsWith("operations/")) {
            String id = suffix.substring(11).split("/", 2)[0];
            if (method.equals("DELETE")) {
                assertTrue(suffix.endsWith("/delete"));
                if (failParentDelete && id.equals(parentId)) {
                    status = 503; body = mapper.createObjectNode();
                } else {
                    deletes.add(id); removed.add(id);
                    status = loseChildAck && id.equals(childId) ? 503 : 200;
                    body = mapper.createObjectNode().put("success", true).put("operation_id", id);
                }
            } else if (removed.contains(id)) body = mapper.createObjectNode().put("operation_id", id).put("status", "not_found");
            else if (id.equals(parentId)) {
                ObjectNode value = mapper.createObjectNode().put("operation_id", id).put("operation_type", "batch_retain").put("status", parentState);
                var children = value.putArray("child_operations");
                if (!hideFirstChild && !removed.contains(childId)) children.addObject().put("operation_id", childId).put("status", childState);
                if (twoChildren && !removed.contains(secondChild)) children.addObject().put("operation_id", secondChild).put("status", "completed"); body = value;
            } else body = mapper.createObjectNode().put("operation_id", id).put("operation_type", "retain").put("status", childState);
        } else { status = 404; body = mapper.createObjectNode(); }
        byte[] bytes = mapper.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type", "application/json");
        try { exchange.sendResponseHeaders(status, 0); exchange.getResponseBody().write(bytes); }
        finally { exchange.close(); }
    }
}
