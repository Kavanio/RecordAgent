package butvan.agent.network.memory.repository;

import butvan.agent.network.memory.client.MemoryRemoteGateway.Target;
import butvan.agent.network.memory.service.MemoryRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 无正文的远端操作账本；每个方法使用短事务，网络调用留在 Adapter。 */
@Repository
public class MemoryRemoteOperationRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public MemoryRemoteOperationRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }
    /** 完成标志只说明已知目标的步骤，不代表派生数据已完整删除。 */
    public record Operation(String id, boolean acknowledged, boolean terminalSeen, boolean documentRequested,
                            boolean documentDeleted, boolean factsChecked, boolean parentRequested, boolean parentDeleted) { }
    public record Child(String id, String status, boolean deleteRequested, boolean deleted) { }

    /** 发送前登记；同 ID 只能匹配相同来源与摘要，已登记请求绝不重复 POST。 */
    public boolean begin(Target target, String hash) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM memory_deletion WHERE space_id=? AND document_id=?
                    AND recipient_fingerprint=? AND operation_id=?)
                    """, Boolean.class, target.spaceId(), target.documentId(), target.recipientFingerprint(), target.operationId())))
                throw new MemoryRequestException(409, "外发目标尚未登记");
            int count = jdbc.update("""
                    INSERT INTO memory_remote_operation(operation_id,space_id,document_id,recipient_fingerprint,payload_hash)
                    VALUES(?,?,?,?,?) ON CONFLICT(operation_id) DO NOTHING
                    """, target.operationId(), target.spaceId(), target.documentId(), target.recipientFingerprint(), hash);
            String existing = jdbc.queryForObject("SELECT payload_hash FROM memory_remote_operation WHERE operation_id=? AND space_id=? AND document_id=? AND recipient_fingerprint=?",
                    String.class, target.operationId(), target.spaceId(), target.documentId(), target.recipientFingerprint());
            if (!hash.equals(existing)) throw new MemoryRequestException(409, "记忆操作内容已变化，不能复用原操作 ID");
            return count == 1;
        }));
    }
    /** 完整目标匹配后才可查询或清理，不能仅凭外部 UUID 访问其他空间。 */
    public Optional<Operation> operation(Target target) {
        return jdbc.query("""
                SELECT * FROM memory_remote_operation WHERE operation_id=? AND space_id=? AND document_id=? AND recipient_fingerprint=?
                """, (rs, n) -> new Operation(rs.getString("operation_id"), rs.getBoolean("acknowledged"), rs.getBoolean("terminal_seen"),
                rs.getBoolean("document_delete_requested"), rs.getBoolean("document_deleted"), rs.getBoolean("facts_checked"),
                rs.getBoolean("parent_delete_requested"), rs.getBoolean("parent_deleted")), target.operationId(), target.spaceId(),
                target.documentId(), target.recipientFingerprint()).stream().findFirst();
    }
    /** 仅记录确认受理，不把受理当成模型处理成功。 */
    public void acknowledge(String operation) { jdbc.update("UPDATE memory_remote_operation SET acknowledged=1 WHERE operation_id=?", operation); }

    /** 保存观测到的全部子指针；既有子项无故消失或终态倒退时拒绝信任此次终态。 */
    public boolean observe(String operation, String parentStatus, List<Child> children, boolean terminal) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Map<String, Child> latest = new HashMap<>(); children.forEach(child -> latest.put(child.id(), child));
            for (var previous : children(operation)) {
                var current = latest.get(previous.id());
                if (current == null && !previous.deleteRequested()) return false;
                if (current != null && ended(previous.status()) && !ended(current.status())) return false;
            }
            for (var child : children) jdbc.update("""
                    INSERT INTO memory_remote_child(operation_id,child_id,status) VALUES(?,?,?)
                    ON CONFLICT(operation_id,child_id) DO UPDATE SET status=excluded.status
                    """, operation, child.id(), child.status());
            jdbc.update("UPDATE memory_remote_operation SET parent_status=?,terminal_seen=? WHERE operation_id=?", parentStatus, terminal, operation);
            return true;
        }));
    }
    /** 清理重启后仍可取得此前观测到的子任务，父行删除不丢失指针。 */
    public List<Child> children(String operation) {
        return jdbc.query("SELECT * FROM memory_remote_child WHERE operation_id=? ORDER BY child_id", (rs, n) ->
                new Child(rs.getString("child_id"), rs.getString("status"), rs.getBoolean("delete_requested"), rs.getBoolean("deleted")), operation);
    }
    /** 固定列枚举避免把调用方字符串拼入 SQL。 */
    public enum Step { DOCUMENT_REQUESTED, DOCUMENT_DELETED, FACTS_CHECKED, PARENT_REQUESTED, PARENT_DELETED }
    /** 每个副作用前记录意图，核对成功后单独记录，允许丢失响应后续做。 */
    public void step(String operation, Step step) {
        String column = switch (step) {
            case DOCUMENT_REQUESTED -> "document_delete_requested";
            case DOCUMENT_DELETED -> "document_deleted";
            case FACTS_CHECKED -> "facts_checked";
            case PARENT_REQUESTED -> "parent_delete_requested";
            case PARENT_DELETED -> "parent_deleted";
        };
        jdbc.update("UPDATE memory_remote_operation SET " + column + "=1 WHERE operation_id=?", operation);
    }
    /** 已知子任务的删除意图与核对结果分别落盘。 */
    public void child(String operation, String child, boolean deleted) {
        jdbc.update("UPDATE memory_remote_child SET delete_requested=1,deleted=? WHERE operation_id=? AND child_id=?", deleted, operation, child);
    }
    private boolean ended(String value) { return value.equals("completed") || value.equals("failed"); }
}
