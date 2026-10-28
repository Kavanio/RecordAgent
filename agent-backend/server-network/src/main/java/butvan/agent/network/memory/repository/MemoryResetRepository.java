package butvan.agent.network.memory.repository;

import butvan.agent.network.memory.client.MemoryRemoteGateway.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

/** 重置专属记忆 SQL；事务由参与方管理，网络等待期间不持有数据库写事务。 */
@Repository
@RequiredArgsConstructor
public class MemoryResetRepository {
    private final JdbcTemplate jdbc;
    private final MemoryRepository memory;

    /** 屏障建立后一次性冻结资格和目标；同一 resetId 续做不再次增加授权版本。 */
    public String prepare(String owner, String environment, String resetId, boolean configured) {
        String space = memory.space(owner, environment);
        if (jdbc.update("INSERT INTO memory_reset VALUES(?,?,'PENDING') ON CONFLICT DO NOTHING", space, resetId) == 0) return space;
        memory.revoke(space, memory.grant(space).revision());
        var settings = memory.settings(space);
        memory.settings(space, settings.version(), false, false);
        jdbc.update("UPDATE memory_deletion SET reset_id=?,state='PENDING' WHERE space_id=? AND state<>'VERIFIED'", resetId, space);
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM memory_deletion WHERE space_id=? AND reset_id=?", Integer.class, space, resetId);
        status(space, resetId, pending == 0 ? (configured ? "NOT_REQUIRED" : "NOT_INTEGRATED") : "PENDING");
        return space;
    }

    /** 有界读取原任务尚未核对成功的目标，已成功目标不重复清理。 */
    public List<Target> pending(String space, String resetId) {
        return jdbc.query("""
                SELECT * FROM memory_deletion WHERE space_id=? AND reset_id=? AND state<>'VERIFIED'
                ORDER BY document_id LIMIT 100
                """, (rs, n) -> new Target(space, rs.getString("document_id"), rs.getString("recipient_fingerprint"),
                rs.getString("operation_id")), space, resetId);
    }
    /** 每个远端结果单独持久化，允许中断后继续。 */
    public void targetState(Target target, String state) {
        jdbc.update("UPDATE memory_deletion SET state=? WHERE space_id=? AND document_id=?", state, target.spaceId(), target.documentId());
    }
    /** 保存记忆参与方的远端进度，不改变全局屏障。 */
    public void status(String space, String resetId, String status) {
        jdbc.update("UPDATE memory_reset SET remote_status=? WHERE space_id=? AND reset_id=?", status, space, resetId);
    }
    /** 状态查询不创建空间，旧 M1 日志不会被追认远端已清理。 */
    public String statusForOwner(String owner, String environment, String resetId) {
        return jdbc.query("""
                SELECT r.remote_status FROM memory_reset r JOIN memory_space s ON s.id=r.space_id
                WHERE s.owner_id=? AND s.environment=? AND r.reset_id=?
                """, (rs, n) -> rs.getString(1), owner, environment, resetId).stream().findFirst().orElse("NOT_INTEGRATED");
    }
    /** 必要远端目标核对后清除本地个人资格，保留最小目标墓碑。 */
    public void finish(String space, String resetId) {
        jdbc.update("DELETE FROM memory_source_document WHERE space_id=?", space);
        jdbc.update("DELETE FROM memory_sync_cursor WHERE space_id=?", space);
        jdbc.update("DELETE FROM memory_command WHERE space_id=?", space);
        jdbc.update("""
                UPDATE memory_authorization SET recipient_fingerprint='',allow_save=0,allow_recall=0,channels='',
                effective_at=NULL,retention_days=0,daily_write_limit=0,daily_query_limit=0 WHERE space_id=?
                """, space);
        String status = jdbc.queryForObject("SELECT remote_status FROM memory_reset WHERE space_id=? AND reset_id=?", String.class, space, resetId);
        if (!List.of("NOT_REQUIRED", "NOT_INTEGRATED").contains(status)) status(space, resetId, "COMPLETE");
    }
}
