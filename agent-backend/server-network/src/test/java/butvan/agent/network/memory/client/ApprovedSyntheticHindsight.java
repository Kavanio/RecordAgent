package butvan.agent.network.memory.client;

import butvan.agent.agents.memory.MemorySourcePolicy;
import butvan.agent.network.memory.repository.MemoryRemoteOperationRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 用户明确同意后的固定 URL 明文试跑；只在测试 classpath 中存在，不是假装加密的代理。 */
public final class ApprovedSyntheticHindsight {
    public static final URI APPROVED_ENDPOINT = URI.create("http://panel.butvan.top:18888/");
    static final URI INTERNAL_ENDPOINT = URI.create("http://127.0.0.1/recordagent-explicit-plaintext-trial/");
    private ApprovedSyntheticHindsight() { }
    public record Source(String space, String document, String operation, String content, Instant occurredAt) { }
    public record Observation(String kind, int httpStatus, String state, String operationType, int childCount,
                              boolean operationMatches, boolean bankMatches, boolean success, boolean async, int itemCount) { }

    public static Session open(URI endpoint, String token, String chain, MemoryRemoteOperationRepository journal, ObjectMapper mapper) {
        if (!APPROVED_ENDPOINT.equals(endpoint) || token == null || !token.isEmpty())
            throw new IllegalArgumentException("明文例外只允许已确认的固定地址和空 Token");
        var transport = new Transport(mapper, null);
        try {
            var adapter = new HindsightHttpAdapter(INTERNAL_ENDPOINT, "",
                    chain + ";explicit-plaintext-synthetic-target=" + MemorySourcePolicy.hash(endpoint.toString()), journal, mapper, transport);
            return new Session(adapter, transport);
        } catch (RuntimeException failure) { transport.close(); throw failure; }
    }
    public static final class Session implements AutoCloseable {
        private final HindsightHttpAdapter adapter;
        private final Transport transport;
        private Session(HindsightHttpAdapter adapter, Transport transport) { this.adapter = adapter; this.transport = transport; }
        public HindsightHttpAdapter adapter() { return adapter; }
        public List<Observation> observations() { return List.copyOf(transport.observations); }
        public void bind(List<Source> sources, Set<String> knownChildren) { transport.bind(sources, knownChildren); }
        /** 目标空间不存在时 POST 会失败；这里只建空空间元数据，不含正文、不调模型。
         * 建空间是 PUT，不在现有传输层支持的 GET／POST／DELETE 内，因此这里用有界的独立调用。
         */
        public String initializeBank() {
            String path = transport.bank();
            if (path == null) return "NOT_BOUND";
            var body = transport.mapper.createObjectNode().put("name", "RecordAgent synthetic trial " + path.substring(path.lastIndexOf('-') + 1));
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                // 先只读确认空间是否存在；存在时无需重复创建。
                var existing = transport.request("GET", INTERNAL_ENDPOINT.resolve("v1/default/banks/" + path + "/profile"), "", null, deadline, () -> false);
                if (existing.status() == 200) return "READY";
                var reply = transport.request("PUT", INTERNAL_ENDPOINT.resolve("v1/default/banks/" + path), "", body, deadline, () -> false);
                if (reply.status() != 200) return "HTTP_" + reply.status();
                String returned = reply.body().path("bank_id").asText(reply.body().path("id").asText());
                return returned.isEmpty() || returned.equals(path) ? "READY" : "BANK_MISMATCH";
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return "CANCELLED"; }
            catch (IOException | RuntimeException failure) { return "ERROR_" + failure.getClass().getSimpleName(); }
        }
        /** 只读检查复用相同 2500ms 总期限；永远不表示传输加密或删除已验证。 */
        public String check(BooleanSupplier cancelled) {
            long deadline = System.nanoTime() + Duration.ofMillis(2500).toNanos();
            try {
                var version = transport.request("GET", INTERNAL_ENDPOINT.resolve("version"), "", null, deadline, cancelled);
                if (version.status() != 200) return "UNAVAILABLE";
                if (!"1.1.0".equals(version.body().path("api_version").asText())) return "VERSION_MISMATCH";
                var health = transport.request("GET", INTERNAL_ENDPOINT.resolve("health/ready"), "", null, deadline, cancelled);
                if (health.status() != 200 || !"healthy".equals(health.body().path("status").asText())) return "NOT_READY";
                var api = transport.request("GET", INTERNAL_ENDPOINT.resolve("openapi.json"), "", null, deadline, cancelled);
                return api.status() == 200 && HindsightContractCheck.supports(api.body()) ? "INSPECTED" : "CONTRACT_MISMATCH";
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return "CANCELLED"; }
            catch (IOException | RuntimeException failure) { return "UNAVAILABLE"; }
        }
        @Override public void close() { adapter.close(); }
    }

    /** 测试用外部交换 seam；默认仍调用原有有界 HTTP，保留取消、实际流上限和总期限。 */
    @FunctionalInterface interface Exchange {
        HindsightHttpTransport.Reply request(String method, URI target, JsonNode body, long deadline, BooleanSupplier cancelled) throws IOException, InterruptedException;
    }
    static final class Transport extends HindsightHttpTransport {
        private final ObjectMapper mapper;
        private final Exchange exchange;
        private final Map<String, Source> sources = new HashMap<>();
        private final Set<String> children = new HashSet<>();
        private String bank;
        private final List<Observation> observations = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Observation> observations() { return List.copyOf(observations); }
        String bank() { return bank; }
        Transport(ObjectMapper mapper, Exchange exchange) { super(mapper); this.mapper = mapper; this.exchange = exchange; }

        void bind(List<Source> values, Set<String> knownChildren) {
            if (values.size() != 3 || values.stream().map(Source::space).distinct().count() != 1
                    || values.stream().map(Source::operation).distinct().count() != 3
                    || values.stream().map(Source::document).distinct().count() != 3
                    || !new HashSet<>(values.stream().map(Source::content).toList()).equals(new HashSet<>(SyntheticMemoryTrialData.SAMPLES))
                    || values.stream().anyMatch(value -> !HindsightHttpAdapter.identifier(value.space())
                    || !HindsightHttpAdapter.identifier(value.document()) || !HindsightHttpAdapter.uuid(value.operation()) || value.occurredAt() == null)
                    || knownChildren.size() > 96 || knownChildren.stream().anyMatch(id -> !HindsightHttpAdapter.uuid(id)))
                throw new IllegalArgumentException("明文试跑只接受本次完整的三个固定虚构来源");
            String nextBank = "ra-" + values.getFirst().space();
            if (bank != null && !bank.equals(nextBank)) throw new IllegalArgumentException("不能切换已有试跑空间");
            bank = nextBank; sources.clear(); values.forEach(value -> sources.put(value.operation(), value)); children.addAll(knownChildren);
        }
        @Override Reply request(String method, URI uri, String token, JsonNode body, long deadline, BooleanSupplier cancelled) throws IOException, InterruptedException {
            if (!token.isEmpty() || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawPath().contains("%")
                    || !uri.toString().startsWith(INTERNAL_ENDPOINT.toString())) throw new IOException("请求不属于已确认的明文合成试跑");
            String suffix = uri.getRawPath().substring(INTERNAL_ENDPOINT.getRawPath().length());
            String query = uri.getRawQuery();
            boolean probe = method.equals("GET") && query == null && Set.of("version", "health/ready", "openapi.json").contains(suffix) && body == null;
            if (!probe && !allowed(method, suffix, query, body)) {
                observe(method, suffix, body, 0, mapper.createObjectNode().put("status", "LOCAL_REFUSAL"));
                throw new IOException("请求超出固定合成来源或清理范围");
            }
            URI target = APPROVED_ENDPOINT.resolve(suffix + (query == null ? "" : "?" + query));
            Reply reply;
            try {
                reply = exchange == null ? super.request(method, target, "", body, deadline, cancelled)
                        : exchange.request(method, target, body, deadline, cancelled);
            } catch (IOException | InterruptedException failure) {
                observe(method, suffix, body, 0, mapper.createObjectNode().put("status", "IO_UNKNOWN")); throw failure;
            }
            observe(method, suffix, body, reply.status(), reply.body());
            if (method.equals("GET") && "include_payload=false".equals(query) && reply.status() == 200
                    && suffix.equals("v1/default/banks/" + bank + "/operations/" + reply.body().path("operation_id").asText()))
                learnChildren(reply.body());
            return reply;
        }
        private void observe(String method, String path, JsonNode request, int status, JsonNode response) {
            if (observations.size() >= 128) return;
            String kind = path.endsWith("/memories") ? "WRITE" : path.endsWith("/memories/recall") ? "RECALL"
                    : path.contains("/operations/") ? (method.equals("GET") ? "OPERATION_QUERY" : "OPERATION_DELETE")
                    : path.contains("/documents/") ? "DOCUMENT" : path.endsWith("/memories/list") ? "FACTS"
                    : method.equals("PUT") ? "BANK_INIT" : "PROBE";
            String value = response.path("status").asText();
            if (!Set.of("pending", "processing", "completed", "failed", "cancelled", "not_found", "LOCAL_REFUSAL", "IO_UNKNOWN").contains(value)) value = "UNSPECIFIED";
            String type = response.path("operation_type").asText(); if (!Set.of("batch_retain", "retain").contains(type)) type = "UNSPECIFIED";
            String expected = request == null ? path.substring(path.lastIndexOf('/') + 1) : request.path("operation_id").asText();
            observations.add(new Observation(kind, status, value, type, response.path("child_operations").isArray() ? response.path("child_operations").size() : -1,
                    !expected.isEmpty() && expected.equals(response.path("operation_id").asText()), bank != null && bank.equals(response.path("bank_id").asText()),
                    response.path("success").isBoolean() && response.path("success").asBoolean(), response.path("async").isBoolean() && response.path("async").asBoolean(),
                    response.path("items_count").isIntegralNumber() ? response.path("items_count").asInt() : -1));
        }
        private boolean allowed(String method, String path, String query, JsonNode body) {
            // 空空间元数据是保留内容的先决条件；只允许本次绑定的随机空间，正文仍受下面白名单约束。
            if (bank != null && method.equals("PUT") && query == null && path.equals("v1/default/banks/" + bank))
                return body != null && body.size() == 1 && body.path("name").isTextual() && body.path("name").asText().startsWith("RecordAgent synthetic trial ");
            String prefix = "v1/default/banks/" + bank + "/";
            if (bank == null || !path.startsWith(prefix)) return false;
            String suffix = path.substring(prefix.length());
            if (method.equals("GET") && query == null && suffix.equals("profile")) return true;
            if (method.equals("POST") && query == null && suffix.equals("memories")) {
                var source = sources.get(body == null ? "" : body.path("operation_id").asText());
                return source != null && expectedWrite(source).equals(body);
            }
            if (method.equals("POST") && query == null && suffix.equals("memories/recall")) {
                String question = body == null ? "" : body.path("query").asText();
                return SyntheticMemoryTrialData.QUERIES.contains(question) && expectedRecall(question).equals(body);
            }
            if (body != null) return false;
            if (suffix.startsWith("documents/")) return query == null && Set.of("GET", "DELETE").contains(method)
                    && sources.values().stream().anyMatch(source -> suffix.equals("documents/" + source.document()));
            if (suffix.equals("memories/list")) return method.equals("GET") && sources.values().stream()
                    .anyMatch(source -> ("document_id=" + source.document() + "&limit=1&offset=0").equals(query));
            if (suffix.startsWith("operations/")) {
                String id = suffix.substring(11).split("/", 2)[0];
                if (!sources.containsKey(id) && !children.contains(id)) return false;
                return method.equals("GET") && suffix.equals("operations/" + id) && "include_payload=false".equals(query)
                        || method.equals("DELETE") && suffix.equals("operations/" + id + "/delete") && query == null;
            }
            return false;
        }
        private ObjectNode expectedWrite(Source source) {
            var item = mapper.createObjectNode().put("content", source.content()).put("timestamp", source.occurredAt().toString())
                    .put("document_id", source.document()).put("update_mode", "replace");
            item.putObject("metadata").put("source_system", "recordagent").put("role", "user").put("channel", "DESKTOP");
            var body = mapper.createObjectNode().put("async", true).put("operation_id", source.operation()); body.putArray("items").add(item); return body;
        }
        private ObjectNode expectedRecall(String question) {
            var body = mapper.createObjectNode().put("query", question).put("budget", "low").put("max_tokens", 500).put("trace", false);
            body.putArray("types").add("world").add("experience");
            var include = body.putObject("include"); include.putNull("entities"); include.putNull("chunks"); include.putNull("source_facts"); return body;
        }
        private void learnChildren(JsonNode body) {
            String parent = body.path("operation_id").asText(); var rows = body.path("child_operations");
            if (!sources.containsKey(parent) || !rows.isArray() || rows.size() > 32) return;
            for (var row : rows) {
                String child = row.path("operation_id").asText(); if (HindsightHttpAdapter.uuid(child) && !sources.containsKey(child)) children.add(child);
            }
        }
    }
}
