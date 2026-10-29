package butvan.agent.network.memory.repository;

import butvan.agent.network.memory.model.MemoryModels.Meter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/** 召回只认可本空间已核对写入且未失效的来源；不靠供应商返回的 owner 证明所有权。 */
@Repository
@RequiredArgsConstructor
public class MemoryRecallRepository {
    private final JdbcTemplate jdbc;

    /** 来源与任务、接收方同时匹配，未知来源不进入信封。 */
    public Optional<String> verifiedSource(String space, String document, String recipient) {
        return jdbc.query("""
                SELECT d.id FROM memory_source_document d JOIN memory_sync_operation o ON o.source_id=d.id
                WHERE d.space_id=? AND d.document_id=? AND d.recipient_fingerprint=?
                AND d.state='CAPTURED' AND o.state='SUCCEEDED'
                """, (rs, n) -> rs.getString(1), space, document, recipient).stream().findFirst();
    }
    /** 删除仍在途或结果不明时，保守暂停整个受影响空间。 */
    public boolean deletionPending(String space) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_deletion WHERE space_id=? AND state IN ('PENDING','FAILED','UNKNOWN'))",
                Boolean.class, space));
    }
    /**
     * 查询原子预占，不保存查询正文。
     *
     * <p>V1 授权继续共用原 query_calls 上限，保持旧确认语义；V2 把真实召回与 Worker 状态核对
     * 分开计量，避免异步核对把当天召回额度耗尽。</p>
     */
    public boolean reserve(String space, String day, int limit, Meter meter) {
        String column = column(meter);
        jdbc.update("INSERT INTO memory_sync_usage(space_id,day) VALUES(?,?) ON CONFLICT DO NOTHING", space, day);
        return jdbc.update("UPDATE memory_sync_usage SET " + column + "=" + column + "+1 WHERE space_id=? AND day=? AND " + column + "<?",
                space, day, limit) == 1;
    }

    /** 计量列只接受固定的四类，禁止把外部字符串拼进 SQL。 */
    private static String column(Meter meter) {
        return switch (meter) {
            case WRITE -> "write_calls";
            case STATUS -> "status_calls";
            case RECALL -> "recall_calls";
            case LEGACY_QUERY -> "query_calls";
        };
    }
}
