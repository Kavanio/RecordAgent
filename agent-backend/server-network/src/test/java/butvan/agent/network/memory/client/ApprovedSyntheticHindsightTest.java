package butvan.agent.network.memory.client;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** 仅记录外部交换，不连接公开地址；检验明文例外的固定地址、固定正文和凭据隔离。 */
class ApprovedSyntheticHindsightTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ApprovedSyntheticHindsight.Source> sources = java.util.stream.IntStream.range(0, 3)
            .mapToObj(index -> new ApprovedSyntheticHindsight.Source("trial-space", "doc-" + index, UUID.randomUUID().toString(),
                    SyntheticMemoryTrialData.SAMPLES.get(index), Instant.parse("2026-10-28T08:00:00Z"))).toList();

    @Test void refusesAnotherUrlOrAnyTokenWhileNormalAdapterStillRejectsPublicHttp() {
        assertThrows(IllegalArgumentException.class, () -> ApprovedSyntheticHindsight.open(URI.create("http://other.example.invalid/"), "", "chain", null, mapper));
        assertThrows(IllegalArgumentException.class, () -> ApprovedSyntheticHindsight.open(ApprovedSyntheticHindsight.APPROVED_ENDPOINT, "not-sent", "chain", null, mapper));
        assertThrows(IllegalArgumentException.class, () -> new HindsightHttpAdapter(ApprovedSyntheticHindsight.APPROVED_ENDPOINT, "", "chain", null, mapper));
    }

    @Test void approvedRequestUsesTheRealFixedUrlAndPreservesDeadlineAndCancellation() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos(); BooleanSupplier cancel = () -> false;
        var calls = new AtomicInteger();
        try (var transport = new ApprovedSyntheticHindsight.Transport(mapper, (method, target, body, limit, cancelled) -> {
            calls.incrementAndGet(); assertEquals("GET", method); assertEquals(ApprovedSyntheticHindsight.APPROVED_ENDPOINT.resolve("version"), target);
            assertNull(body); assertEquals(deadline, limit); assertSame(cancel, cancelled);
            return new HindsightHttpTransport.Reply(200, mapper.createObjectNode().put("api_version", "1.1.0"));
        })) {
            assertEquals(200, transport.request("GET", ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("version"), "", null, deadline, cancel).status());
            assertEquals(1, calls.get());
            assertThrows(IOException.class, () -> transport.request("GET", ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("version"), "secret", null, deadline, cancel));
            assertEquals(1, calls.get());
        }
    }

    @Test void rejectsPersonalTextExtraFieldsForeignBanksAndUnownedDeletesBeforeExchange() throws Exception {
        var calls = new AtomicInteger();
        try (var transport = new ApprovedSyntheticHindsight.Transport(mapper, (method, target, body, limit, cancel) -> {
            calls.incrementAndGet(); return new HindsightHttpTransport.Reply(200, mapper.createObjectNode());
        })) {
            transport.bind(sources, Set.of());
            URI write = ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("v1/default/banks/ra-trial-space/memories");
            long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            var good = writeBody();
            transport.request("POST", write, "", good, deadline, () -> false); assertEquals(1, calls.get());
            var personal = good.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) personal.path("items").get(0)).put("content", "这不是固定虚构内容");
            assertThrows(IOException.class, () -> transport.request("POST", write, "", personal, deadline, () -> false));
            assertThrows(IOException.class, () -> transport.request("POST", write, "", good.deepCopy().put("api_key", "sentinel"), deadline, () -> false));
            assertThrows(IOException.class, () -> transport.request("POST", ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("v1/default/banks/existing-bank/memories"), "", good, deadline, () -> false));
            assertThrows(IOException.class, () -> transport.request("DELETE", ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("v1/default/banks/ra-trial-space/documents/foreign-doc"), "", null, deadline, () -> false));
            assertThrows(IOException.class, () -> transport.request("DELETE", ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("v1/default/banks/ra-trial-space"), "", null, deadline, () -> false));
            assertEquals(1, calls.get());
        }
    }

    @Test void fixedQueriesOnlyAndChildDeletionRequiresKnownOwnership() throws Exception {
        String child = UUID.randomUUID().toString(); var calls = new AtomicInteger();
        try (var transport = new ApprovedSyntheticHindsight.Transport(mapper, (method, target, body, limit, cancel) -> {
            calls.incrementAndGet(); var reply = mapper.createObjectNode().put("operation_id", sources.getFirst().operation());
            reply.putArray("child_operations").addObject().put("operation_id", child);
            return new HindsightHttpTransport.Reply(200, reply);
        })) {
            transport.bind(sources, Set.of()); long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
            URI childDelete = ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("v1/default/banks/ra-trial-space/operations/" + child + "/delete");
            assertThrows(IOException.class, () -> transport.request("DELETE", childDelete, "", null, deadline, () -> false));
            URI parent = ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("v1/default/banks/ra-trial-space/operations/" + sources.getFirst().operation() + "?include_payload=false");
            transport.request("GET", parent, "", null, deadline, () -> false);
            transport.request("DELETE", childDelete, "", null, deadline, () -> false);
            URI recall = ApprovedSyntheticHindsight.INTERNAL_ENDPOINT.resolve("v1/default/banks/ra-trial-space/memories/recall");
            var body = mapper.createObjectNode().put("query", SyntheticMemoryTrialData.QUERIES.getFirst()).put("budget", "low").put("max_tokens", 500).put("trace", false);
            body.putArray("types").add("world").add("experience");
            var include = body.putObject("include"); include.putNull("entities"); include.putNull("chunks"); include.putNull("source_facts");
            transport.request("POST", recall, "", body, deadline, () -> false);
            assertThrows(IOException.class, () -> transport.request("POST", recall, "", body.deepCopy().put("query", "私人的真实问题"), deadline, () -> false));
            assertEquals(3, calls.get());
        }
    }

    @Test void realAdapterSerializationPassesTheFixedBodyGuardBeforeAnyExternalWrite() throws Exception {
        SyntheticDatabaseFixture.run(directory.resolve("wire"), (jdbc, transactions, root) -> {
            var journal = new butvan.agent.network.memory.repository.MemoryRemoteOperationRepository(jdbc, transactions);
            var calls = new AtomicInteger();
            try (var transport = new ApprovedSyntheticHindsight.Transport(mapper, (method, target, body, limit, cancel) -> {
                calls.incrementAndGet();
                return new HindsightHttpTransport.Reply(200, mapper.createObjectNode().put("success", true).put("async", true)
                        .put("bank_id", target.getPath().split("/")[4]).put("items_count", 1).put("operation_id", body.path("operation_id").asText()));
            }); var gateway = new HindsightHttpAdapter(ApprovedSyntheticHindsight.INTERNAL_ENDPOINT, "", "chain", journal, mapper, transport)) {
                var user = new butvan.agent.agents.identity.CurrentUserProvider();
                var clock = Clock.fixed(Instant.parse("2026-10-28T08:00:00Z"), ZoneOffset.UTC);
                var memory = new butvan.agent.network.memory.service.MemoryService(new butvan.agent.network.memory.repository.MemoryRepository(jdbc),
                        new butvan.agent.agents.reset.ResetBarrier(new butvan.agent.agents.storage.AgentStorageProperties(root)), user, clock, gateway, transactions, "test");
                memory.authorize(new butvan.agent.network.memory.model.MemoryModels.Authorization(0, gateway.recipientFingerprint(), true, true,
                        Set.of(butvan.agent.agents.memory.MemorySourceRegistry.Channel.DESKTOP), 1, 3, 30, true, "grant"));
                memory.saveSettings(memory.settings().version(), true, true);
                var registrations = new ArrayList<ApprovedSyntheticHindsight.Source>();
                for (var text : SyntheticMemoryTrialData.SAMPLES) {
                    var source = new butvan.agent.agents.memory.MemorySourceRegistry.Source(user.currentUserId(), "session", UUID.randomUUID().toString(),
                            "message", clock.instant(), butvan.agent.agents.memory.MemorySourceRegistry.Channel.DESKTOP, "a".repeat(64), 0);
                    var receipt = memory.capture(source).orElseThrow(); String id = UUID.randomUUID().toString(); memory.recordRemoteOperation(receipt, id);
                    var row = new butvan.agent.network.memory.repository.MemorySyncRepository(jdbc).source(receipt.sourceId()).orElseThrow();
                    registrations.add(new ApprovedSyntheticHindsight.Source(row.space(), row.document(), id, text, row.occurredAt()));
                }
                transport.bind(registrations, Set.of());
                var first = registrations.getFirst();
                var target = new MemoryRemoteGateway.Target(first.space(), first.document(), gateway.recipientFingerprint(), first.operation());
                assertEquals(MemoryWriteGateway.Outcome.ACCEPTED, gateway.submit(new MemoryWriteGateway.Write(first.operation(), target, first.content(),
                        first.occurredAt(), butvan.agent.agents.memory.MemorySourceRegistry.Channel.DESKTOP), () -> false), transport.observations().toString());
                assertEquals(1, calls.get());
            }
        });
    }
    /** 仅初始化本用例的临时 SQLite，不连接真实存储或服务。 */
    private static final class SyntheticDatabaseFixture {
        @FunctionalInterface interface Work { void run(org.springframework.jdbc.core.JdbcTemplate jdbc,
                org.springframework.transaction.PlatformTransactionManager transactions, java.nio.file.Path root) throws Exception; }
        static void run(java.nio.file.Path root, Work work) throws Exception {
            java.nio.file.Files.createDirectory(root);
            var properties = new butvan.agent.network.config.database.LocalDatabaseProperties(); properties.setPath(root.resolve("test.db"));
            var config = new butvan.agent.network.config.database.LocalDatabaseConfiguration(); var dataSource = config.localDataSource(properties);
            config.flyway(dataSource).migrate(); work.run(config.jdbcTemplate(dataSource), config.transactionManager(dataSource), root);
        }
    }

    private com.fasterxml.jackson.databind.node.ObjectNode writeBody() {
        var source = sources.getFirst(); var body = mapper.createObjectNode().put("async", true).put("operation_id", source.operation());
        var item = body.putArray("items").addObject().put("content", source.content()).put("timestamp", source.occurredAt().toString())
                .put("document_id", source.document()).put("update_mode", "replace");
        item.putObject("metadata").put("source_system", "recordagent").put("role", "user").put("channel", "DESKTOP"); return body;
    }
}
