package butvan.agent.network.memory.repository;

import butvan.agent.agents.memory.MemorySourceRegistry.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;

/** 来源补偿与任务租约的 SQLite 实现；调用方持有重置租约，HTTP 不进入这些事务。 */
@Repository
@RequiredArgsConstructor
public class MemorySyncRepository {
    private final JdbcTemplate jdbc;
    public record SourceRow(long ordinal, String id, String space, long generation, long revision, String session,
                            String turn, String message, Instant occurredAt, Channel channel, String hash,
                            String document, String recipient) {
        public Receipt receipt() { return new Receipt(id, space, revision, generation); }
    }
    public record Operation(String id, String sourceId, String state, int attempts, int queries, String token) { }
    private static final RowMapper<SourceRow> SOURCE = (rs, n) -> new SourceRow(rs.getLong("ordinal"), rs.getString("id"),
            rs.getString("space_id"), rs.getLong("generation"), rs.getLong("authorization_revision"),
            rs.getString("session_id"), rs.getString("turn_id"), rs.getString("message_id"), Instant.parse(rs.getString("occurred_at")),
            Channel.valueOf(rs.getString("channel")), rs.getString("content_hash"), rs.getString("document_id"), rs.getString("recipient_fingerprint"));
    private static final RowMapper<Operation> OPERATION = (rs, n) -> new Operation(rs.getString("id"), rs.getString("source_id"),
            rs.getString("state"), rs.getInt("attempts"), rs.getInt("queries"), rs.getString("claim_token"));

    /** 只定位已登记资格，不从 transcript 枚举历史。 */
    public Optional<SourceRow> source(String owner, String environment, String session, String turn) {
        return jdbc.query("""
                SELECT d.rowid AS ordinal,d.* FROM memory_source_document d JOIN memory_space s ON s.id=d.space_id
                WHERE s.owner_id=? AND s.environment=? AND d.session_id=? AND d.turn_id=? AND d.state='CAPTURED'
                ORDER BY d.rowid DESC LIMIT 1
                """, SOURCE, owner, environment, session, turn).stream().findFirst();
    }
    /** 任务只可读取仍有效的来源映射。 */
    public Optional<SourceRow> source(String sourceId) {
        return jdbc.query("SELECT rowid AS ordinal,* FROM memory_source_document WHERE id=? AND state='CAPTURED'",
                SOURCE, sourceId).stream().findFirst();
    }
    /** 管理查看包含停用来源，必须另校验空间，不赋予重新发送资格。 */
    public Optional<SourceRow> sourceForManagement(String space, String sourceId) {
        return jdbc.query("SELECT rowid AS ordinal,* FROM memory_source_document WHERE space_id=? AND id=?", SOURCE, space, sourceId).stream().findFirst();
    }
    /** 持久化循环游标，尚未结束的老轮次不会饿死后续成功轮次。 */
    public List<SourceRow> candidates(String space, int limit) {
        jdbc.update("INSERT INTO memory_sync_cursor(space_id) VALUES(?) ON CONFLICT DO NOTHING", space);
        Long cursor = jdbc.queryForObject("SELECT last_rowid FROM memory_sync_cursor WHERE space_id=?", Long.class, space);
        var rows = jdbc.query("SELECT rowid AS ordinal,* FROM memory_source_document WHERE space_id=? AND state='CAPTURED' AND rowid>? ORDER BY rowid LIMIT ?",
                SOURCE, space, cursor, limit);
        jdbc.update("UPDATE memory_sync_cursor SET last_rowid=? WHERE space_id=?", rows.isEmpty() ? 0 : rows.getLast().ordinal(), space);
        return rows;
    }
    /** 每个来源只建一项任务，入队有上限；满队列由后续补偿再次尝试。 */
    public void enqueue(SourceRow source) {
        jdbc.update("""
                INSERT INTO memory_sync_operation(id,source_id,state) SELECT ?,?,'PENDING'
                WHERE (SELECT COUNT(*) FROM memory_sync_operation o JOIN memory_source_document d ON d.id=o.source_id
                WHERE d.space_id=? AND o.state NOT IN ('SUCCEEDED','FAILED','CANCELLED'))<1000
                ON CONFLICT(source_id) DO NOTHING
                """, UUID.randomUUID().toString(), source.id(), source.space());
    }
    /** 原文变化或终态不成功时永久停用该来源，不能被补偿复活。 */
    public void invalidate(String sourceId) {
        jdbc.update("UPDATE memory_source_document SET state='REVOKED' WHERE id=?", sourceId);
        jdbc.update("UPDATE memory_sync_operation SET state='CANCELLED',error_code='SOURCE_INVALID' WHERE source_id=? AND state<>'SUCCEEDED'", sourceId);
    }
    /** 限定到当前空间和到期窗口，已完成任务不再领取。 */
    public List<Operation> due(String space, long now, int limit) {
        return jdbc.query("""
                SELECT o.* FROM memory_sync_operation o JOIN memory_source_document d ON d.id=o.source_id
                WHERE d.space_id=? AND ((o.state='CLAIMED' AND o.claimed_until<=?) OR
                (o.state IN ('PENDING','RETRY_WAIT','SUBMITTED','UNKNOWN') AND o.next_attempt_at<=?))
                ORDER BY o.next_attempt_at,o.id LIMIT ?
                """, OPERATION, space, now, now, limit);
    }
    /** 短事务内先预占当天额度再 CAS 领取；失败调用不退额度。 */
    public Optional<String> claim(Operation operation, String space, String day, long now, int dailyLimit, boolean query) {
        jdbc.update("INSERT INTO memory_sync_usage(space_id,day) VALUES(?,?) ON CONFLICT DO NOTHING", space, day);
        String column = query ? "query_calls" : "write_calls";
        int reserved = jdbc.update("UPDATE memory_sync_usage SET " + column + "=" + column + "+1 WHERE space_id=? AND day=? AND " + column + "<?",
                space, day, dailyLimit);
        if (reserved == 0) return Optional.empty();
        String token = UUID.randomUUID().toString();
        int claimed = jdbc.update("""
                UPDATE memory_sync_operation SET state='CLAIMED',claim_token=?,claimed_until=?,attempts=attempts+?,queries=queries+?
                WHERE id=? AND ((state='CLAIMED' AND claimed_until<=?) OR (state=? AND state<>'CLAIMED'))
                """, token, now + 30_000, query ? 0 : 1, query ? 1 : 0, operation.id(), now, operation.state());
        if (claimed == 0) {
            jdbc.update("UPDATE memory_sync_usage SET " + column + "=" + column + "-1 WHERE space_id=? AND day=?", space, day);
            return Optional.empty();
        }
        return Optional.of(token);
    }
    /** 只有当前领取者可发布，过期 Worker 的迟到结果不能覆盖新状态。 */
    public void finish(String id, String token, String state, long nextAttempt, String reason) {
        jdbc.update("UPDATE memory_sync_operation SET state=?,next_attempt_at=?,error_code=?,claimed_until=0 WHERE id=? AND claim_token=? AND state='CLAIMED'",
                state, nextAttempt, reason, id, token);
    }
    /** 公开模块查询使用此快照；不返回正文、用户路径或密钥。 */
    public List<Operation> operations(String space) {
        return jdbc.query("SELECT o.* FROM memory_sync_operation o JOIN memory_source_document d ON d.id=o.source_id WHERE d.space_id=? ORDER BY o.id LIMIT 100", OPERATION, space);
    }
}
