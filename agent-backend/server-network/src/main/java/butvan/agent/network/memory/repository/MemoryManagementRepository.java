package butvan.agent.network.memory.repository;

import butvan.agent.network.memory.client.MemoryRemoteGateway.Target;
import butvan.agent.network.memory.service.MemoryRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** 来源管理 SQL；纠正正文独立保存，任务和墓碑不含正文。 */
@Repository
@RequiredArgsConstructor
public class MemoryManagementRepository {
    private final JdbcTemplate jdbc;

    /** 仅向应用层返回内部投影，Controller 另行映射 DTO。 */
    public record Document(long cursor, String id, long version, Instant occurredAt, String channel, String state,
                           String syncState, String deletionState, String correction, String operationId) { }
    /** 稳定管理任务，幂等重放不重做来源写入。 */
    public record Operation(String id, String sourceId, String kind, String hash, long version, String state, Instant updatedAt) { }

    private static final String DOCUMENTS = """
            SELECT d.rowid AS cursor,d.*,COALESCE(s.state,'NOT_QUEUED') AS sync_state,
            COALESCE(t.state,'NOT_REQUIRED') AS deletion_state,c.corrected_text,
            (SELECT o.id FROM memory_management_operation o WHERE o.source_id=d.id ORDER BY o.result_version DESC LIMIT 1) AS management_id
            FROM memory_source_document d LEFT JOIN memory_sync_operation s ON s.source_id=d.id
            LEFT JOIN memory_deletion t ON t.space_id=d.space_id AND t.document_id=d.document_id
            LEFT JOIN memory_correction c ON c.source_id=d.id
            """;

    /** 按稳定 rowid 游标有界读取；不把聊天正文展开到列表。 */
    public List<Document> documents(String space, long cursor, int limit) {
        return jdbc.query(DOCUMENTS + " WHERE d.space_id=? AND d.rowid>? ORDER BY d.rowid LIMIT ?", this::document, space, cursor, limit);
    }
    /** ID 必须同时属于当前空间。 */
    public Document document(String space, String id) {
        return jdbc.query(DOCUMENTS + " WHERE d.space_id=? AND d.id=?", this::document, space, id).stream().findFirst()
                .orElseThrow(() -> new MemoryRequestException(404, "记忆来源不存在或已被重置"));
    }
    private Document document(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Document(rs.getLong("cursor"), rs.getString("id"), rs.getLong("version"), Instant.parse(rs.getString("occurred_at")),
                rs.getString("channel"), rs.getString("state"), rs.getString("sync_state"), rs.getString("deletion_state"),
                rs.getString("corrected_text"), rs.getString("management_id"));
    }
    /** 空间内 requestId 唯一，不能被用于其他来源或正文。 */
    public Optional<Operation> command(String space, String request) {
        return jdbc.query("SELECT * FROM memory_management_operation WHERE space_id=? AND request_id=?", this::operation, space, request).stream().findFirst();
    }
    /** 只读取当前用户的任务。 */
    public Operation operation(String space, String id) {
        return jdbc.query("SELECT * FROM memory_management_operation WHERE space_id=? AND id=?", this::operation, space, id).stream().findFirst()
                .orElseThrow(() -> new MemoryRequestException(404, "记忆处理任务不存在或已被重置"));
    }
    private Operation operation(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Operation(rs.getString("id"), rs.getString("source_id"), rs.getString("kind"), rs.getString("payload_hash"),
                rs.getLong("result_version"), rs.getString("state"), Instant.parse(rs.getString("updated_at")));
    }
    /** 同一事务内停用来源、取消任务、建立删除标记并保存独立纠正。 */
    public Operation mutate(String space, String id, long version, String kind, String text, String request, String hash, Instant now) {
        if (jdbc.update("UPDATE memory_source_document SET state='REVOKED',version=version+1 WHERE space_id=? AND id=? AND version=?", space, id, version) != 1)
            throw new MemoryRequestException(409, "记忆来源已变化，请刷新后重试");
        jdbc.update("UPDATE memory_sync_operation SET state='CANCELLED',error_code='SOURCE_WITHDRAWN' WHERE source_id=?", id);
        jdbc.update("""
                UPDATE memory_deletion SET state='PENDING' WHERE space_id=? AND document_id=
                (SELECT document_id FROM memory_source_document WHERE id=?) AND state<>'VERIFIED'
                """, space, id);
        jdbc.update("DELETE FROM memory_correction WHERE source_id=?", id);
        if (kind.equals("CORRECT")) jdbc.update("INSERT INTO memory_correction VALUES(?,?,?,?)", id, version + 1, text, now.toString());
        String task = UUID.randomUUID().toString();
        boolean pending = target(space, id).isPresent();
        jdbc.update("INSERT INTO memory_management_operation VALUES(?,?,?,?,?,?,?,?,?)", task, space, id, request, kind, hash,
                version + 1, pending ? "PENDING" : "SUCCEEDED", now.toString());
        return operation(space, task);
    }
    /** 已验证的目标无需再删；目标在验证之前一直保留。 */
    public Optional<Target> target(String space, String source) {
        return jdbc.query("""
                SELECT t.* FROM memory_deletion t JOIN memory_source_document d ON d.document_id=t.document_id AND d.space_id=t.space_id
                WHERE d.id=? AND t.space_id=? AND t.state<>'VERIFIED'
                """, (rs, n) -> new Target(space, rs.getString("document_id"), rs.getString("recipient_fingerprint"), rs.getString("operation_id")), source, space).stream().findFirst();
    }
    /** 更新任务与目标的权威状态；远端核对完成前不返回成功。 */
    public void status(String space, Operation operation, String state, Instant now) {
        jdbc.update("UPDATE memory_management_operation SET state=?,updated_at=? WHERE space_id=? AND id=?", state, now.toString(), space, operation.id());
        if (List.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(state)) {
            jdbc.update("""
                    UPDATE memory_deletion SET state=? WHERE space_id=? AND document_id=
                    (SELECT document_id FROM memory_source_document WHERE id=?) AND state<>'VERIFIED'
                    """, state.equals("SUCCEEDED") ? "VERIFIED" : state, space, operation.sourceId());
        }
    }
}
