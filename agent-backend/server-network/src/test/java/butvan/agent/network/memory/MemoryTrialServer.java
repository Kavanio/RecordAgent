package butvan.agent.network.memory;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅本机的 Hindsight 合约替身；不加载用户配置，不调用真实模型。 */
final class MemoryTrialServer implements AutoCloseable {
    final ObjectMapper mapper = new ObjectMapper();
    final HttpServer server;
    final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger reads = new AtomicInteger(), posts = new AtomicInteger(), bankInitializations = new AtomicInteger(), recalls = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();
    final Map<String, JsonNode> writes = new ConcurrentHashMap<>();
    final Set<String> removed = ConcurrentHashMap.newKeySet();
    volatile String version = "1.1.0";
    volatile boolean rejectBank;
    volatile boolean rejectTaskCleanup;
    MemoryTrialServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads); server.createContext("/", this::handle); server.start();
    }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
        ObjectNode body = mapper.createObjectNode(); int status = 200;
        if (path.equals("/version")) { reads.incrementAndGet(); body.put("api_version", version); }
        else if (path.equals("/health/ready")) { reads.incrementAndGet(); body.put("status", "healthy"); }
        else if (path.equals("/openapi.json")) { reads.incrementAndGet(); body = contract(); }
        else if (path.startsWith("/v1/default/banks/")) {
            String suffix = path.substring("/v1/default/banks/".length());
            String bank = suffix.split("/", 2)[0]; suffix = suffix.contains("/") ? suffix.substring(bank.length() + 1) : "";
            if (suffix.isEmpty() && method.equals("PUT")) {
                bankInitializations.incrementAndGet(); body.put("bank_id", rejectBank ? "wrong-bank" : bank);
            } else if (suffix.equals("memories") && method.equals("POST")) {
                posts.incrementAndGet(); var value = mapper.readTree(exchange.getRequestBody());
                String id = value.path("operation_id").asText(); writes.put(id, value);
                body.put("success", true).put("async", true).put("bank_id", bank).put("items_count", 1).put("operation_id", id);
            } else if (suffix.equals("memories/recall")) {
                recalls.incrementAndGet(); var results = body.putArray("results");
                for (var value : writes.values()) {
                    var item = value.path("items").get(0);
                    results.addObject().put("type", "world").put("text", item.path("content").asText()).put("document_id", item.path("document_id").asText());
                }
            } else if (suffix.startsWith("operations/")) {
                String id = suffix.substring(11).split("/", 2)[0];
                body.put("operation_id", id);
                if (method.equals("DELETE")) {
                    if (rejectTaskCleanup) status = 503;
                    else { removed.add(id); body.put("success", true); }
                }
                else if (removed.contains(id) || !writes.containsKey(id)) body.put("status", "not_found");
                else {
                    body.put("operation_type", "batch_retain").put("status", "completed");
                    String child = UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString();
                    if (!removed.contains(child)) body.putArray("child_operations").addObject().put("operation_id", child).put("status", "completed");
                    else body.putArray("child_operations");
                }
            } else if (suffix.startsWith("documents/")) {
                String id = suffix.substring(10);
                if (method.equals("DELETE")) { removed.add(id); body.put("success", true).put("document_id", id); }
                else { status = removed.contains(id) ? 404 : 200; body.put("id", id).put("bank_id", bank); }
            } else if (suffix.equals("memories/list")) { body.put("total", 0).putArray("items"); }
            else status = 404;
        } else status = 404;
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
            var parts = route.split(" ", 2);
            paths.withObject("/" + (bank + parts[1]).replace("~", "~0").replace("/", "~1")).putObject(parts[0]);
        }
        var schemas = api.putObject("components").putObject("schemas");
        for (String spec : List.of("RetainRequest items async operation_id", "RetainResponse success bank_id async operation_id operation_ids",
                "MemoryItem content timestamp document_id update_mode", "RecallRequest query types max_tokens budget", "RecallResult text type document_id",
                "OperationStatusResponse operation_id status child_operations")) {
            var fields = spec.split(" "); var properties = schemas.putObject(fields[0]).putObject("properties");
            for (int i = 1; i < fields.length; i++) properties.putObject(fields[i]);
        }
        return api;
    }
    public void close() { server.stop(0); threads.shutdownNow(); }
}
