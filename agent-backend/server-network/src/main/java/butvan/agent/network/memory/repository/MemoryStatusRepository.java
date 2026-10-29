package butvan.agent.network.memory.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只读聚合本地权威账本，用于展示处理覆盖与积压；不保存正文，也不探测远端。 */
@Repository
@RequiredArgsConstructor
public class MemoryStatusRepository {
    private final JdbcTemplate jdbc;

    /** 当日四类调用计数；V1 旧授权只有合并查询列，其余列为零。 */
    public Usage usage(String space, String day) {
        return jdbc.query("SELECT write_calls,query_calls,status_calls,recall_calls FROM memory_sync_usage WHERE space_id=? AND day=?",
                (rs, n) -> new Usage(rs.getInt("write_calls"), rs.getInt("query_calls"),
                        rs.getInt("status_calls"), rs.getInt("recall_calls")), space, day)
                .stream().findFirst().orElse(new Usage(0, 0, 0, 0));
    }

    /** 来源总数与当前仍可召回的数量。 */
    public long sources(String space, String state) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM memory_source_document WHERE space_id=? AND state=?",
                Long.class, space, state);
        return count == null ? 0 : count;
    }

    /** 来源处理任务按状态分组，用于区分待处理、已处理、失败与未知。 */
    public Map<String, Long> operations(String space) {
        var counts = new LinkedHashMap<String, Long>();
        jdbc.query("""
                SELECT o.state AS state, COUNT(*) AS total FROM memory_sync_operation o
                JOIN memory_source_document d ON d.id=o.source_id WHERE d.space_id=? GROUP BY o.state ORDER BY o.state
                """, rs -> { counts.put(rs.getString("state"), rs.getLong("total")); }, space);
        return counts;
    }

    /** 最老未终结任务时间；无待办时为空，不把“没有记录”当成已全量处理。 */
    public Instant oldestPending(String space) {
        return jdbc.query("""
                SELECT MIN(o.next_attempt_at) FROM memory_sync_operation o
                JOIN memory_source_document d ON d.id=o.source_id
                WHERE d.space_id=? AND o.state IN ('PENDING','CLAIMED','SUBMITTED','RETRY_WAIT','UNKNOWN')
                """, rs -> rs.next() ? Instant.ofEpochMilli(rs.getLong(1)) : null, space);
    }

    /** 当日过滤原因计数；只有原因码，没有正文。 */
    public Map<String, Long> skipped(String space, String day) {
        var counts = new LinkedHashMap<String, Long>();
        jdbc.query("SELECT reason,skipped FROM memory_source_skip WHERE space_id=? AND day=? ORDER BY reason",
                rs -> { counts.put(rs.getString("reason"), rs.getLong("skipped")); }, space, day);
        return counts;
    }

    /** 调用计数快照；与响应 DTO 分开，避免仓储类型泄漏到协议层。 */
    public record Usage(int writeCalls, int legacyCombinedQueries, int statusCalls, int recallCalls) { }
}
