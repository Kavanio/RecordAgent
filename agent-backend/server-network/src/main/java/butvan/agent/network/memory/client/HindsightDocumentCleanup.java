package butvan.agent.network.memory.client;

import butvan.agent.network.memory.repository.MemoryRemoteOperationRepository;
import butvan.agent.network.memory.repository.MemoryRemoteOperationRepository.Step;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;

/** 有界、可恢复的远端目标清理：文档、直接事实、已知子任务与父任务全部清除后返回 VERIFIED。 */
final class HindsightDocumentCleanup {
    private final HindsightHttpTransport http;
    private final MemoryRemoteOperationRepository repository;
    private final URI endpoint;
    private final String token;
    private final java.util.function.Function<String, String> bank;

    HindsightDocumentCleanup(HindsightHttpTransport http, MemoryRemoteOperationRepository repository, URI endpoint, String token) {
        this(http, repository, endpoint, token, space -> "ra-" + space);
    }

    HindsightDocumentCleanup(HindsightHttpTransport http, MemoryRemoteOperationRepository repository, URI endpoint,
                             String token, java.util.function.Function<String, String> bank) {
        this.http = http;
        this.repository = repository;
        this.endpoint = endpoint;
        this.token = token;
        this.bank = bank;
    }

    MemoryRemoteGateway.DeletionResult clean(MemoryRemoteGateway.Target target, long deadline) throws IOException, InterruptedException {
        String id = target.operationId();
        var operation = repository.operation(target).orElseThrow();
        if (operation.parentDeleted()) {
            return MemoryRemoteGateway.DeletionResult.VERIFIED;
        }
        // 文档步骤完成后仍须取得当前任务观测；仅在父历史已发起删除后使用原意图核对缺失。
        if (!operation.parentRequested()) {
            var response = call("GET", target, "operations/" + id + "?include_payload=false", deadline);
            if (response.status() != 200) {
                return MemoryRemoteGateway.DeletionResult.UNKNOWN;
            }
            var state = HindsightOperationStatus.observeKnownCleanup(response.body(), target, repository);
            if (state != MemoryWriteGateway.Outcome.SUCCEEDED && state != MemoryWriteGateway.Outcome.FAILED) {
                return MemoryRemoteGateway.DeletionResult.UNKNOWN;
            }
            operation = repository.operation(target).orElseThrow();
        }
        if (!operation.terminalSeen()) {
            return MemoryRemoteGateway.DeletionResult.UNKNOWN;
        }
        var children = repository.children(id);
        if (children.isEmpty() || children.size() > 32
                || children.stream().anyMatch(child -> !java.util.Set.of("completed", "failed").contains(child.status()))) {
            return MemoryRemoteGateway.DeletionResult.UNKNOWN;
        }
        if (!operation.documentDeleted()) {
            String document = "documents/" + target.documentId();
            var before = call("GET", target, document, deadline);
            boolean absent = before.status() == 404 && operation.documentRequested();
            if (!absent) {
                if (before.status() != 200
                        || !target.documentId().equals(before.body().path("id").asText())
                        || !bank.apply(target.spaceId()).equals(before.body().path("bank_id").asText())) {
                    return MemoryRemoteGateway.DeletionResult.UNKNOWN;
                }
                repository.step(id, Step.DOCUMENT_REQUESTED);
                var deleted = call("DELETE", target, document, deadline);
                if (deleted.status() != 200 || !success(deleted.body(), "document_id", target.documentId())) {
                    return MemoryRemoteGateway.DeletionResult.UNKNOWN;
                }
                absent = call("GET", target, document, deadline).status() == 404;
            }
            if (!absent) {
                return MemoryRemoteGateway.DeletionResult.UNKNOWN;
            }
            repository.step(id, Step.DOCUMENT_DELETED);
        }
        var facts = call("GET", target, "memories/list?document_id=" + target.documentId() + "&limit=1&offset=0", deadline);
        if (facts.status() != 200 || !facts.body().path("items").isArray() || !facts.body().path("items").isEmpty()
                || !facts.body().path("total").isIntegralNumber() || facts.body().path("total").asLong() != 0) {
            return MemoryRemoteGateway.DeletionResult.UNKNOWN;
        }
        if (call("GET", target, "documents/" + target.documentId(), deadline).status() != 404) {
            return MemoryRemoteGateway.DeletionResult.UNKNOWN;
        }
        repository.step(id, Step.FACTS_CHECKED);
        for (var child : repository.children(id)) {
            if (child.deleted()) {
                continue;
            }
            if (child.deleteRequested() && absentOperation(target, child.id(), deadline)) {
                repository.child(id, child.id(), true);
                continue;
            }
            repository.child(id, child.id(), false);
            var removed = call("DELETE", target, "operations/" + child.id() + "/delete", deadline);
            if (removed.status() != 200 || !success(removed.body(), "operation_id", child.id()) || !absentOperation(target, child.id(), deadline)) {
                return MemoryRemoteGateway.DeletionResult.UNKNOWN;
            }
            repository.child(id, child.id(), true);
        }
        operation = repository.operation(target).orElseThrow();
        if (operation.parentRequested() && absentOperation(target, id, deadline)) {
            repository.step(id, Step.PARENT_DELETED);
            return MemoryRemoteGateway.DeletionResult.VERIFIED;
        }
        repository.step(id, Step.PARENT_REQUESTED);
        var parent = call("DELETE", target, "operations/" + id + "/delete", deadline);
        if (parent.status() == 200 && success(parent.body(), "operation_id", id) && absentOperation(target, id, deadline)) {
            repository.step(id, Step.PARENT_DELETED);
            return MemoryRemoteGateway.DeletionResult.VERIFIED;
        }
        return MemoryRemoteGateway.DeletionResult.UNKNOWN;
    }
    private boolean absentOperation(MemoryRemoteGateway.Target target, String id, long deadline) throws IOException, InterruptedException {
        var response = call("GET", target, "operations/" + id + "?include_payload=false", deadline);
        return response.status() == 200 && id.equals(response.body().path("operation_id").asText()) && response.body().path("status").asText().equals("not_found");
    }
    private boolean success(JsonNode body, String field, String id) {
        return body.path("success").isBoolean() && body.path("success").asBoolean() && id.equals(body.path(field).asText());
    }
    private HindsightHttpTransport.Reply call(String method, MemoryRemoteGateway.Target target, String path, long deadline) throws IOException, InterruptedException {
        return http.request(method, endpoint.resolve("v1/default/banks/" + bank.apply(target.spaceId()) + "/" + path), token, null, deadline, () -> false);
    }
}
