package butvan.agent.network.memory.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 只校验已核实 1.1.0 合约的必要字段；结构存在不等于删除语义已获证明。 */
final class HindsightContractCheck {
    private HindsightContractCheck() { }
    static boolean supports(JsonNode api) {
        if (!api.path("info").path("version").asText().equals("1.1.0")) return false;
        String bank = "/v1/default/banks/{bank_id}";
        for (String route : List.of("post " + bank + "/memories", "post " + bank + "/memories/recall",
                "get " + bank + "/operations/{operation_id}", "delete " + bank + "/operations/{operation_id}/delete",
                "get " + bank + "/documents/{document_id}", "delete " + bank + "/documents/{document_id}")) {
            var parts = route.split(" ", 2);
            if (!api.path("paths").path(parts[1]).path(parts[0]).isObject()) return false;
        }
        JsonNode schemas = api.path("components").path("schemas");
        return fields(schemas, "RetainRequest", "items", "async", "operation_id")
                && fields(schemas, "RetainResponse", "success", "bank_id", "async", "operation_id", "operation_ids")
                && fields(schemas, "MemoryItem", "content", "timestamp", "document_id", "update_mode")
                && fields(schemas, "RecallRequest", "query", "types", "max_tokens", "budget")
                && fields(schemas, "RecallResult", "text", "type", "document_id")
                && fields(schemas, "OperationStatusResponse", "operation_id", "status", "child_operations");
    }
    private static boolean fields(JsonNode schemas, String name, String... fields) {
        JsonNode properties = schemas.path(name).path("properties");
        for (String field : fields) if (!properties.path(field).isObject()) return false;
        return true;
    }
}
