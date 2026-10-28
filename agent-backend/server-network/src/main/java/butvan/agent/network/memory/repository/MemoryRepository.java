package butvan.agent.network.memory.repository;

import butvan.agent.agents.memory.MemorySourceRegistry.*;
import butvan.agent.network.memory.model.MemoryModels.*;
import butvan.agent.network.memory.service.MemoryRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** 记忆专属 SQLite Adapter；仅保存控制事实，事务由应用入口负责。 */
@Repository
@RequiredArgsConstructor
public class MemoryRepository {
    private final JdbcTemplate jdbc;

    /** 随数据库持久化的随机空间；不同后端或环境不共享 local-default。 */
    public String space(String owner, String environment) {
        jdbc.update("INSERT INTO memory_space(id,owner_id,environment) VALUES(?,?,?) ON CONFLICT(owner_id,environment) DO NOTHING",
                UUID.randomUUID().toString(), owner, environment);
        String id = jdbc.queryForObject("SELECT id FROM memory_space WHERE owner_id=? AND environment=?", String.class, owner, environment);
        jdbc.update("INSERT INTO memory_settings(space_id) VALUES(?) ON CONFLICT DO NOTHING", id);
        jdbc.update("INSERT INTO memory_authorization(space_id) VALUES(?) ON CONFLICT DO NOTHING", id);
        return id;
    }

    /** 读取单一空间的独立开关与乐观锁版本。 */
    public Settings settings(String space) {
        return jdbc.queryForObject("SELECT * FROM memory_settings WHERE space_id=?", (rs, n) ->
                new Settings(space, rs.getLong("version"), rs.getBoolean("auto_save"), rs.getBoolean("auto_recall")), space);
    }

    /** 读取当前授权快照，权限判断留在应用入口。 */
    public Grant grant(String space) {
        return jdbc.queryForObject("SELECT * FROM memory_authorization WHERE space_id=?", (rs, n) -> {
            String channels = rs.getString("channels");
            Set<Channel> decoded = channels.isEmpty() ? Set.of() : Arrays.stream(channels.split(","))
                    .map(Channel::valueOf).collect(Collectors.toUnmodifiableSet());
            String effective = rs.getString("effective_at");
            return new Grant(rs.getLong("revision"), rs.getBoolean("active"), rs.getString("recipient_fingerprint"),
                    rs.getBoolean("allow_save"), rs.getBoolean("allow_recall"), decoded,
                    effective == null ? null : Instant.parse(effective), rs.getInt("retention_days"),
                    rs.getInt("daily_write_limit"), rs.getInt("daily_query_limit"));
        }, space);
    }

    /** 单条 CAS，冲突不覆盖其他窗口保存结果。 */
    public void settings(String space, long expected, boolean save, boolean recall) {
        conflict(jdbc.update("UPDATE memory_settings SET auto_save=?,auto_recall=?,version=version+1 WHERE space_id=? AND version=?",
                save, recall, space, expected));
    }

    /** CAS 替换授权并使旧来源失效，不能用新版本追认旧轮次。 */
    public void authorize(String space, Authorization input, Instant now) {
        conflict(jdbc.update("""
                UPDATE memory_authorization SET revision=revision+1,active=1,recipient_fingerprint=?,
                allow_save=?,allow_recall=?,channels=?,effective_at=?,retention_days=?,daily_write_limit=?,daily_query_limit=?
                WHERE space_id=? AND revision=?
                """, input.recipientFingerprint(), input.allowSave(), input.allowRecall(),
                input.channels().stream().map(Enum::name).sorted().collect(Collectors.joining(",")), now.toString(),
                input.retentionDays(), input.dailyWriteLimit(), input.dailyQueryLimit(), space, input.expectedRevision()));
        invalidate(space);
    }

    /** 按预期 revision 撤权；保留来源映射供远端清理。 */
    public void revoke(String space, long expected) {
        conflict(jdbc.update("UPDATE memory_authorization SET revision=revision+1,active=0 WHERE space_id=? AND revision=?", space, expected));
        invalidate(space);
    }

    /** 停用旧来源，不删除仍可能需要远端清理的目标。 */
    public void invalidate(String space) {
        jdbc.update("""
                UPDATE memory_sync_operation SET state='CANCELLED',error_code='AUTHORIZATION_CHANGED'
                WHERE source_id IN (SELECT id FROM memory_source_document WHERE space_id=?)
                AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')
                """, space);
        jdbc.update("UPDATE memory_source_document SET state='REVOKED' WHERE space_id=?", space);
    }

    /** 查询当前空间的幂等命令结果，不跨用户复用请求 ID。 */
    public Optional<Command> command(String space, String request) {
        return jdbc.query("SELECT * FROM memory_command WHERE space_id=? AND request_id=?", (rs, n) ->
                new Command(rs.getString("kind"), rs.getString("payload_hash"), rs.getLong("result_revision"),
                        Instant.parse(rs.getString("effective_at"))), space, request).stream().findFirst();
    }

    /** 仅保存请求摘要与安全结果，不保存授权请求正文。 */
    public void command(String space, String request, String kind, String hash, AuthorizationResult result) {
        jdbc.update("INSERT INTO memory_command VALUES(?,?,?,?,?,?)", space, request, kind, hash, result.revision(), result.effectiveAt().toString());
    }

    /** 来源唯一性不含授权版本，重新授权不能重新登记同一旧轮次。 */
    public Optional<Receipt> capture(String space, Source source, Grant grant) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO memory_source_document(id,space_id,generation,session_id,turn_id,message_id,occurred_at,
                channel,content_hash,authorization_revision,state,document_id,recipient_fingerprint)
                VALUES(?,?,?,?,?,?,?,?,?,?,'CAPTURED',?,?) ON CONFLICT(space_id,generation,session_id,turn_id) DO NOTHING
                """, id, space, source.generation(), source.sessionId(), source.turnId(), source.messageId(),
                source.occurredAt().toString(), source.channel().name(), source.contentHash(), grant.revision(), "ra-" + id, grant.fingerprint());
        return jdbc.query("""
                SELECT * FROM memory_source_document WHERE space_id=? AND generation=? AND session_id=? AND turn_id=?
                AND message_id=? AND content_hash=? AND channel=? AND occurred_at=? AND state='CAPTURED' AND authorization_revision=?
                """, (rs, n) -> new Receipt(rs.getString("id"), space, rs.getLong("authorization_revision"), source.generation()),
                space, source.generation(), source.sessionId(), source.turnId(), source.messageId(), source.contentHash(),
                source.channel().name(), source.occurredAt().toString(), grant.revision()).stream().findFirst();
    }

    /** 核对凭据指向的来源仍在当前空间且未被撤回。 */
    public boolean current(Receipt receipt, String space) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM memory_source_document WHERE id=? AND space_id=? AND generation=?
                AND authorization_revision=? AND state='CAPTURED')
                """, Boolean.class, receipt.sourceId(), space, receipt.generation(), receipt.authorizationRevision()));
    }

    /** 外部副作用发生前先保存可能写入的目标；此记录本身不授权网络发送。 */
    public void recordRemoteOperation(Receipt receipt, String operationId) {
        conflict(jdbc.update("""
                UPDATE memory_source_document SET remote_possible=1,remote_operation_id=? WHERE id=? AND space_id=?
                AND state='CAPTURED' AND (remote_operation_id IS NULL OR remote_operation_id=?)
                """, operationId, receipt.sourceId(), receipt.spaceId(), operationId));
        jdbc.update("""
                INSERT INTO memory_deletion(space_id,document_id,recipient_fingerprint,operation_id,state)
                SELECT space_id,document_id,recipient_fingerprint,remote_operation_id,'TRACKED'
                FROM memory_source_document WHERE id=? ON CONFLICT(space_id,document_id) DO NOTHING
                """, receipt.sourceId());
    }

    private void conflict(int count) {
        if (count != 1) throw new MemoryRequestException(409, "记忆设置或授权已变化，请刷新后重试");
    }
}
