package butvan.agent.network.memory.client;

import butvan.agent.agents.memory.MemorySourcePolicy;
import butvan.agent.network.memory.repository.MemoryRemoteOperationRepository;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 1.1.0 协议 Adapter；默认未配置，真实聊天试用必须另经连接确认与用途授权。
 * 调用方必须持有既有重置租约或全局重置清理范围；这里不替代用途授权。
 */
public final class HindsightHttpAdapter implements MemoryWriteGateway, MemoryRecallGateway, MemoryRemoteGateway, AutoCloseable {
    private final HindsightHttpTransport http;
    private final MemoryRemoteOperationRepository operations;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String token;
    private final String fingerprint;
    private final HindsightDocumentCleanup cleanup;
    private final boolean trial;
    private final String selectedBankId;
    private final Set<String> initializedSpaces = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 只接受受控 HTTPS 或字面本机 loopback；空 Token 表示不附加接口鉴权。 */
    public HindsightHttpAdapter(URI endpoint, String token, String processingChain, MemoryRemoteOperationRepository operations, ObjectMapper mapper) {
        this(endpoint, token, processingChain, operations, mapper, null);
    }
    /** 内部传输 seam 供隔离验证复用协议；公开构造与生产连接校验保持不变。 */
    HindsightHttpAdapter(URI endpoint, String token, String processingChain, MemoryRemoteOperationRepository operations,
                         ObjectMapper mapper, HindsightHttpTransport transport) {
        this(endpoint, token, processingChain, operations, mapper, transport, false, false);
    }
    /** 仅供已明确确认风险的试用配置使用；公网明文仅允许空 Token，正常构造仍拒绝。 */
    public static HindsightHttpAdapter forTrial(URI endpoint, String token, String processingChain,
            MemoryRemoteOperationRepository operations, ObjectMapper mapper, boolean allowPlaintext) {
        return new HindsightHttpAdapter(endpoint, token, processingChain, operations, mapper, null, allowPlaintext, true);
    }
    private HindsightHttpAdapter(URI endpoint, String token, String processingChain, MemoryRemoteOperationRepository operations,
                                 ObjectMapper mapper, HindsightHttpTransport transport, boolean allowPlaintext, boolean trial) {
        this(endpoint, token, processingChain, operations, mapper, transport, allowPlaintext, trial, "");
    }
    public static HindsightHttpAdapter forTrial(URI endpoint, String token, String processingChain,
            MemoryRemoteOperationRepository operations, ObjectMapper mapper, boolean allowPlaintext, String bankId) {
        return new HindsightHttpAdapter(endpoint, token, processingChain, operations, mapper, null, allowPlaintext, true, bankId);
    }
    private HindsightHttpAdapter(URI endpoint, String token, String processingChain, MemoryRemoteOperationRepository operations,
            ObjectMapper mapper, HindsightHttpTransport transport, boolean allowPlaintext, boolean trial, String bankId) {
        selectedBankId = bankId == null ? "" : bankId;
        if (!selectedBankId.isEmpty() && !identifier(selectedBankId)) throw new IllegalArgumentException("记忆库 ID 无效");
        this.endpoint = validate(endpoint, token, processingChain, allowPlaintext);
        this.token = token; this.operations = operations; this.mapper = mapper; this.trial = trial;
        fingerprint = MemorySourcePolicy.hash(this.endpoint + "\n" + processingChain + "\n1.1.0\n" + MemorySourcePolicy.hash(token)
                + (trial ? "\nreal-chat-trial-v1-unverified-erasure" : "")
                + (selectedBankId.isEmpty() ? "" : "\nbank=" + selectedBankId));
        http = transport == null ? new HindsightHttpTransport(mapper) : transport;
        cleanup = new HindsightDocumentCleanup(http, operations, this.endpoint, token, this::bank);
    }
    @Override public String recipientFingerprint() { return fingerprint; }

    /** 原请求先持久化摘要，受理未知时不重复 POST，即使服务端支持 UUID 去重。 */
    @Override public Outcome submit(Write request, BooleanSupplier cancelled) {
        if (request == null || !target(request.target()) || !Objects.equals(request.operationId(), request.target().operationId())
                || request.occurredAt() == null || request.channel() == null || !MemorySourcePolicy.eligibleText(request.content()) || cancelled.getAsBoolean()) return Outcome.UNKNOWN;
        String hash = MemorySourcePolicy.hash(request.target().spaceId() + "\n" + request.target().documentId() + "\n" + request.occurredAt()
                + "\n" + request.channel() + "\n" + request.content());
        try {
            if (!operations.begin(request.target(), hash)) return query(request.operationId(), request.target(), cancelled);
            long deadline = deadline();
            // 原目标及发送意图已落盘后，才可初始化本应用的随机空间；不使用原有 bank。
            if (trial && selectedBankId.isEmpty() && !initializeSpace(request.target().spaceId(), deadline, cancelled)) return Outcome.UNKNOWN;
            var item = mapper.createObjectNode().put("content", request.content()).put("timestamp", request.occurredAt().toString())
                    .put("document_id", request.target().documentId()).put("update_mode", "replace");
            item.putObject("metadata").put("source_system", "recordagent").put("role", "user").put("channel", request.channel().name());
            var body = mapper.createObjectNode().put("async", true).put("operation_id", request.operationId());
            body.putArray("items").add(item);
            var response = http.request("POST", uri(request.target().spaceId(), "memories"), token, body, deadline, cancelled);
            JsonNode value = response.body();
            if (response.status() != 200 || !value.path("success").isBoolean() || !value.path("success").asBoolean()
                    || !value.path("async").isBoolean() || !value.path("async").asBoolean()
                    || !bank(request.target().spaceId()).equals(value.path("bank_id").asText())
                    || !value.path("items_count").isIntegralNumber() || value.path("items_count").asInt() != 1
                    || !request.operationId().equals(value.path("operation_id").asText()) || !singleOperation(value.path("operation_ids"), request.operationId())) return Outcome.UNKNOWN;
            operations.acknowledge(request.operationId());
            return Outcome.ACCEPTED;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Outcome.UNKNOWN; }
        catch (IOException | RuntimeException failure) { return unknown("submit", failure); }
    }

    /** 只查询预先登记的原操作；父／子缺失、取消或来源身份不符均不发布成功。 */
    @Override public Outcome query(String operationId, Target target, BooleanSupplier cancelled) {
        if (!target(target) || !target.operationId().equals(operationId) || cancelled.getAsBoolean() || operations.operation(target).isEmpty()) return Outcome.UNKNOWN;
        try {
            var response = http.request("GET", uri(target.spaceId(), "operations/" + operationId + "?include_payload=false"), token, null, deadline(), cancelled);
            if (response.status() != 200) return Outcome.UNKNOWN;
            return HindsightOperationStatus.observe(response.body(), target, operations);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Outcome.UNKNOWN; }
        catch (IOException | RuntimeException failure) { return unknown("query", failure); }
    }

    /** 首期仅接收 world／experience 的直接来源，所有片段还需上层权威来源复核。 */
    @Override public List<Candidate> recall(Query request, Control control) {
        if (request == null || !identifier(request.spaceId()) || request.query() == null || !MemorySourcePolicy.eligibleText(request.query())
                || request.query().codePointCount(0, request.query().length()) > 1000 || request.maxTokens() < 1 || request.limit() < 1 || control.isCancelled()) return List.of();
        var body = mapper.createObjectNode().put("query", request.query()).put("budget", "low").put("max_tokens", Math.min(500, request.maxTokens())).put("trace", false);
        body.putArray("types").add("world").add("experience");
        var include = body.putObject("include"); include.putNull("entities"); include.putNull("chunks"); include.putNull("source_facts");
        try {
            var response = http.request("POST", uri(request.spaceId(), "memories/recall"), token, body, control.deadlineNanos(), control::isCancelled);
            var rows = response.body().path("results");
            if (response.status() != 200 || !rows.isArray()) return List.of();
            List<Candidate> result = new ArrayList<>(); Set<String> seen = new HashSet<>();
            int inspected = 0;
            for (var row : rows) {
                if (++inspected > 16 || result.size() >= Math.min(4, request.limit()) || control.isCancelled()) break;
                String type = row.path("type").asText(), text = row.path("text").asText(), document = row.path("document_id").asText();
                if (!Set.of("world", "experience").contains(type) || !identifier(document) || !MemorySourcePolicy.eligibleText(text) || !seen.add(document + "\n" + text)) continue;
                result.add(new Candidate(text, List.of(document), true));
            }
            return control.isCancelled() ? List.of() : List.copyOf(result);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return List.of(); }
        catch (IOException | RuntimeException failure) { unknown("recall", failure); return List.of(); }
    }

    /** 清理已知来源和任务载荷；缺少完整派生／清单证明，始终保留 UNKNOWN。 */
    @Override public DeletionResult deleteAndVerify(Target target) {
        if (!target(target) || operations.operation(target).isEmpty()) return DeletionResult.UNKNOWN;
        try { return cleanup.clean(target, deadline()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return DeletionResult.UNKNOWN; }
        catch (IOException | RuntimeException failure) { unknown("delete", failure); return DeletionResult.UNKNOWN; }
    }

    private boolean initializeSpace(String space, long deadline, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (initializedSpaces.contains(space)) return true;
        if (initializedSpaces.size() >= 64) return false;
        var metadata = mapper.createObjectNode().put("name", "RecordAgent " + space);
        var reply = http.request("PUT", endpoint.resolve("v1/default/banks/" + bank(space)), token, metadata, deadline, cancelled);
        if (reply.status() != 200 || !bank(space).equals(reply.body().path("bank_id").asText())) return false;
        initializedSpaces.add(space); return true;
    }

    private boolean target(Target target) {
        return target != null && identifier(target.spaceId()) && identifier(target.documentId()) && fingerprint.equals(target.recipientFingerprint()) && uuid(target.operationId());
    }
    private boolean singleOperation(JsonNode ids, String expected) {
        return ids.isMissingNode() || ids.isNull() || (ids.isArray() && ids.size() == 1 && expected.equals(ids.get(0).asText()));
    }
    private URI uri(String space, String suffix) { return endpoint.resolve("v1/default/banks/" + bank(space) + "/" + suffix); }
    private String bank(String space) { return selectedBankId.isEmpty() ? "ra-" + space : selectedBankId; }
    private long deadline() { return System.nanoTime() + Duration.ofSeconds(5).toNanos(); }
    static boolean identifier(String value) { return value != null && value.matches("[a-zA-Z0-9_-]{1,128}"); }
    static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private URI validate(URI value, String token, String chain, boolean allowPlaintext) {
        if (value == null || value.getHost() == null || value.getScheme() == null || value.getRawUserInfo() != null
                || value.getRawQuery() != null || value.getRawFragment() != null || value.getPort() == 0 || value.getPort() > 65535
                || token == null || token.length() > 8192 || token.chars().anyMatch(c -> c < 32 || c == 127)
                || chain == null || chain.isBlank() || chain.length() > 500 || chain.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("记忆服务连接或处理链配置无效");
        boolean secure = value.getScheme().equals("https");
        boolean local = value.getScheme().equals("http") && Set.of("127.0.0.1", "[::1]", "::1", "localhost").contains(value.getHost());
        boolean acceptedPlaintext = allowPlaintext && value.getScheme().equals("http") && token.isEmpty();
        if (!secure && !local && !acceptedPlaintext)
            throw new IllegalArgumentException("记忆数据连接须使用 HTTPS 或本机 loopback");
        String path = Objects.requireNonNullElse(value.getRawPath(), "");
        if (path.contains("%") || path.contains("..") || path.contains("//")) throw new IllegalArgumentException("记忆服务路径无效");
        return URI.create(value.toString().endsWith("/") ? value.toString() : value + "/");
    }
    private Outcome unknown(String stage, Exception failure) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("记忆 HTTP 结果未知：stage={},errorType={}", stage, failure.getClass().getSimpleName());
        return Outcome.UNKNOWN;
    }
    @Override public void close() { http.close(); }
}
