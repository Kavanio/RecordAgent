package butvan.agent.network.memory.client;

import butvan.agent.network.memory.repository.MemoryRemoteOperationRepository;
import butvan.agent.network.memory.repository.MemoryRemoteOperationRepository.Child;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** 固定父／子任务的有界结构和终态判断；不解析或持久化任务正文。 */
final class HindsightOperationStatus {
    private HindsightOperationStatus() { }
    static MemoryWriteGateway.Outcome observe(JsonNode value, MemoryRemoteGateway.Target target, MemoryRemoteOperationRepository repository) {
        if (!target.operationId().equals(value.path("operation_id").asText()) || !value.path("operation_type").asText().equals("batch_retain")) return MemoryWriteGateway.Outcome.UNKNOWN;
        String state = value.path("status").asText();
        if (!Set.of("pending", "processing", "completed", "failed", "cancelled", "not_found").contains(state)) return MemoryWriteGateway.Outcome.UNKNOWN;
        JsonNode rows = value.path("child_operations");
        if (!rows.isArray() || rows.isEmpty() || rows.size() > 32) return MemoryWriteGateway.Outcome.UNKNOWN;
        List<Child> children = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (var row : rows) {
            String id = row.path("operation_id").asText(), childState = row.path("status").asText();
            if (!HindsightHttpAdapter.uuid(id) || id.equals(target.operationId()) || !ids.add(id)
                    || !Set.of("pending", "processing", "completed", "failed", "cancelled").contains(childState)) return MemoryWriteGateway.Outcome.UNKNOWN;
            children.add(new Child(id, childState, false, false));
        }
        boolean ended = Set.of("completed", "failed").contains(state) && children.stream().allMatch(child -> Set.of("completed", "failed").contains(child.status()));
        if (!repository.observe(target.operationId(), state, children, ended)) return MemoryWriteGateway.Outcome.UNKNOWN;
        if (state.equals("completed") && children.stream().allMatch(child -> child.status().equals("completed"))) return MemoryWriteGateway.Outcome.SUCCEEDED;
        if (state.equals("failed") && ended) return MemoryWriteGateway.Outcome.FAILED;
        if (Set.of("pending", "processing").contains(state)) return MemoryWriteGateway.Outcome.ACCEPTED;
        return MemoryWriteGateway.Outcome.UNKNOWN;
    }
}
