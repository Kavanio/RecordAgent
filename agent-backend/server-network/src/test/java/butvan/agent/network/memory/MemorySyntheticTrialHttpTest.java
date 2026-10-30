package butvan.agent.network.memory;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 固定合成来源通过真实 Worker／召回／管理链路；上游仅为本机假 HTTP。 */
class MemorySyntheticTrialHttpTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> writes = new ConcurrentHashMap<>();
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    private final AtomicInteger posts = new AtomicInteger(), recalls = new AtomicInteger();

    @Test void fixedSourcesResumeWithoutRepostingRecallIsCappedAndWithdrawalStaysUnverified() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(threads);
        server.createContext("/", this::handle); server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        Path root = directory.resolve("recordagent-synthetic-trial-http");
        try {
            List<MemorySyntheticTrial.Task> tasks;
            try (var trial = new MemorySyntheticTrial(root, endpoint, "", "synthetic-chain")) {
                assertEquals("PREPARED", trial.run(MemorySyntheticTrial.Action.PREPARE).status());
                var submitted = trial.run(MemorySyntheticTrial.Action.SYNC);
                assertEquals("SYNC_CHECKED", submitted.status());
                tasks = submitted.operations();
                assertEquals(3, posts.get()); assertTrue(tasks.stream().allMatch(task -> task.state().equals("SUBMITTED")));
            }
            // 遵守实际 Worker 的持久化退避，不绕开 due／claim 契约推进时钟。
            Thread.sleep(1100);
            try (var trial = new MemorySyntheticTrial(root, endpoint, "", "synthetic-chain")) {
                var synced = trial.run(MemorySyntheticTrial.Action.SYNC);
                assertTrue(synced.operations().stream().allMatch(task -> task.state().equals("SUCCEEDED")));
                assertEquals(tasks.stream().map(MemorySyntheticTrial.Task::id).toList(), synced.operations().stream().map(MemorySyntheticTrial.Task::id).toList());
                for (int index = 0; index < 3; index++) {
                    var result = trial.run(MemorySyntheticTrial.Action.RECALL);
                    assertEquals("SUCCESS", result.status()); assertEquals(index + 1, result.recallUsed());
                    assertEquals(3, result.matches().size());
                    assertTrue(result.matches().stream().noneMatch(match -> match.content().contains("foreign-sentinel")));
                }
            }
            try (var trial = new MemorySyntheticTrial(root, endpoint, "", "synthetic-chain")) {
                assertEquals("TRIAL_QUERY_LIMIT", trial.run(MemorySyntheticTrial.Action.RECALL).status());
                assertEquals(3, recalls.get()); assertEquals(3, posts.get());
                var withdrawn = trial.run(MemorySyntheticTrial.Action.WITHDRAW);
                assertEquals("WITHDRAWN_REMOTE_UNVERIFIED", withdrawn.status());
                assertEquals(3, withdrawn.deletions().size()); assertFalse(withdrawn.remoteErasureVerified());
                var printed = mapper.readTree(MemoryHindsightSyntheticTrialIT.formatReport(withdrawn));
                assertTrue(printed.path("deletions").get(0).path("updatedAt").isTextual());
                assertEquals(withdrawn.deletions().getFirst().updatedAt(),
                        java.time.Instant.parse(printed.path("deletions").get(0).path("updatedAt").asText()));
                assertTrue(withdrawn.deletions().stream().allMatch(operation -> operation.state().equals("SUCCEEDED")));
                assertEquals(3, posts.get(), "清理不能重发合成正文");
            }
        } finally { server.stop(0); threads.shutdownNow(); }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
        JsonNode body; int status = 200;
        if (path.equals("/version")) body = mapper.createObjectNode().put("api_version", "1.1.0");
        else if (path.equals("/health/ready")) body = mapper.createObjectNode().put("status", "healthy");
        else if (path.equals("/openapi.json")) body = contract();
        else {
            String suffix = path.substring(path.indexOf("/banks/") + 7);
            String bank = suffix.substring(0, suffix.indexOf('/')); suffix = suffix.substring(suffix.indexOf('/') + 1);
            if (suffix.equals("memories") && method.equals("POST")) {
                posts.incrementAndGet(); var value = mapper.readTree(exchange.getRequestBody());
                String id = value.path("operation_id").asText(); writes.put(id, value);
                body = mapper.createObjectNode().put("success", true).put("async", true).put("bank_id", bank).put("items_count", 1).put("operation_id", id);
            } else if (suffix.equals("memories/recall")) {
                recalls.incrementAndGet(); var result = mapper.createObjectNode(); var rows = result.putArray("results");
                for (var value : writes.values()) {
                    var item = value.path("items").get(0);
                    rows.addObject().put("type", "experience").put("text", item.path("content").asText()).put("document_id", item.path("document_id").asText());
                }
                rows.addObject().put("type", "world").put("text", "foreign-sentinel").put("document_id", "foreign-document"); body = result;
            } else if (suffix.startsWith("operations/")) {
                String id = suffix.substring(11).split("/", 2)[0];
                if (method.equals("DELETE")) { removed.add(id); body = mapper.createObjectNode().put("success", true).put("operation_id", id); }
                else if (removed.contains(id)) body = mapper.createObjectNode().put("operation_id", id).put("status", "not_found");
                else {
                    var result = mapper.createObjectNode().put("operation_id", id).put("operation_type", "batch_retain").put("status", "completed");
                    String child = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
                    var children = result.putArray("child_operations");
                    if (!removed.contains(child)) children.addObject().put("operation_id", child).put("status", "completed"); body = result;
                }
            } else if (suffix.startsWith("documents/")) {
                String id = suffix.substring(10);
                if (method.equals("DELETE")) { removed.add(id); body = mapper.createObjectNode().put("success", true).put("document_id", id); }
                else { if (removed.contains(id)) status = 404; body = mapper.createObjectNode().put("id", id).put("bank_id", bank); }
            } else if (suffix.equals("memories/list")) { var result = mapper.createObjectNode().put("total", 0); result.putArray("items"); body = result; }
            else { status = 404; body = mapper.createObjectNode(); }
        }
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
        finally { exchange.close(); }
    }
    private ObjectNode contract() {
        var api = mapper.createObjectNode(); api.putObject("info").put("version", "1.1.0");
        var paths = api.putObject("paths"); String bank = "/v1/default/banks/{bank_id}";
        for (String route : List.of("post /memories", "post /memories/recall", "get /operations/{operation_id}",
                "delete /operations/{operation_id}/delete", "get /documents/{document_id}", "delete /documents/{document_id}")) {
            var parts = route.split(" ", 2); paths.withObject("/" + (bank + parts[1]).replace("~", "~0").replace("/", "~1")).putObject(parts[0]);
        }
        var schemas = api.putObject("components").putObject("schemas");
        for (String spec : List.of("RetainRequest items async operation_id", "RetainResponse success bank_id async operation_id operation_ids",
                "MemoryItem content timestamp document_id update_mode", "RecallRequest query types max_tokens budget", "RecallResult text type document_id",
                "OperationStatusResponse operation_id status child_operations")) {
            var fields = spec.split(" "); var properties = schemas.putObject(fields[0]).putObject("properties");
            for (int index = 1; index < fields.length; index++) properties.putObject(fields[index]);
        }
        return api;
    }
}
