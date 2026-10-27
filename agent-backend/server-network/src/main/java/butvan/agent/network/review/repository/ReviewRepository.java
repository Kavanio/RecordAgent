package butvan.agent.network.review.repository;

import butvan.agent.network.review.model.ReviewModels.*;
import butvan.agent.network.review.service.ReviewPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 复盘领域 SQLite Adapter；仅访问 review 表，事务与权限由领域入口负责。 */
@Repository
@RequiredArgsConstructor
public class ReviewRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Settings> SETTINGS = (rs, row) -> new Settings(rs.getString("owner_id"),
            rs.getBoolean("enabled"), ReviewPolicy.decode(rs.getString("periods")), ReviewPolicy.decode(rs.getString("domains")),
            rs.getInt("history_days"), rs.getInt("daily_limit"), rs.getString("provider_id"), rs.getString("model_name"),
            rs.getString("connection_fingerprint"), rs.getInt("revision"), Instant.parse(rs.getString("updated_at")));
    private static final RowMapper<Grant> GRANT = (rs, row) -> new Grant(rs.getString("id"), rs.getString("owner_id"),
            rs.getString("policy_version"), ReviewPolicy.decode(rs.getString("periods")), ReviewPolicy.decode(rs.getString("domains")),
            rs.getInt("history_days"), rs.getInt("daily_limit"), rs.getString("connection_fingerprint"), rs.getString("state"),
            rs.getInt("revision"), Instant.parse(rs.getString("created_at")),
            rs.getString("revoked_at") == null ? null : Instant.parse(rs.getString("revoked_at")));
    private static final RowMapper<Goal> GOAL = (rs, row) -> new Goal(rs.getString("id"), rs.getString("owner_id"),
            rs.getString("title"), rs.getString("constraint_text"), rs.getString("success_criterion"), rs.getString("state"),
            rs.getInt("revision"), Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at")));

    /** 未配置用户返回关闭的领域默认值，不产生数据库写入。 */
    public Settings settings(String owner) {
        return jdbc.query("SELECT * FROM review_settings WHERE owner_id=?", SETTINGS, owner).stream()
                .findFirst().orElseGet(() -> Settings.defaults(owner));
    }
    /** 乐观锁首次插入与更新；不会覆盖另一个请求保存的配置。 */
    public void saveSettings(Settings value, int expectedRevision) {
        int count;
        if (expectedRevision == 0) {
            count = jdbc.update("""
                    INSERT INTO review_settings(owner_id,enabled,periods,domains,history_days,daily_limit,
                        provider_id,model_name,connection_fingerprint,revision,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(owner_id) DO NOTHING
                    """, value.owner(), value.enabled(), ReviewPolicy.encode(value.periods()), ReviewPolicy.encode(value.domains()),
                    value.historyDays(), value.dailyLimit(), value.providerId(), value.modelName(), value.fingerprint(),
                    value.revision(), value.updatedAt().toString());
        } else {
            count = jdbc.update("""
                    UPDATE review_settings SET enabled=?,periods=?,domains=?,history_days=?,daily_limit=?,
                        provider_id=?,model_name=?,connection_fingerprint=?,revision=?,updated_at=?
                    WHERE owner_id=? AND revision=?
                    """, value.enabled(), ReviewPolicy.encode(value.periods()), ReviewPolicy.encode(value.domains()),
                    value.historyDays(), value.dailyLimit(), value.providerId(), value.modelName(), value.fingerprint(),
                    value.revision(), value.updatedAt().toString(), value.owner(), expectedRevision);
        }
        if (count != 1) throw new IllegalArgumentException("复盘配置已变化，请刷新后重试");
    }
    /** 部分唯一索引保证每用户至多一个有效授权。 */
    public Optional<Grant> activeGrant(String owner) {
        return jdbc.query("SELECT * FROM review_grant WHERE owner_id=? AND state='ACTIVE'", GRANT, owner).stream().findFirst();
    }
    /** 授权查询同时约束用户和授权 ID，避免跨用户探测。 */
    public Optional<Grant> grant(String owner, String id) {
        return jdbc.query("SELECT * FROM review_grant WHERE owner_id=? AND id=?", GRANT, owner, id).stream().findFirst();
    }
    /** 新授权替代旧授权，审计行保留，但旧授权不再有效。 */
    public void insertGrant(Grant value) {
        jdbc.update("UPDATE review_grant SET state='REVOKED',revision=revision+1,revoked_at=? WHERE owner_id=? AND state='ACTIVE'",
                value.createdAt().toString(), value.owner());
        jdbc.update("""
                INSERT INTO review_grant(id,owner_id,policy_version,periods,domains,history_days,daily_limit,
                    connection_fingerprint,state,revision,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, value.id(), value.owner(), value.policyVersion(), ReviewPolicy.encode(value.periods()),
                ReviewPolicy.encode(value.domains()), value.historyDays(), value.dailyLimit(), value.fingerprint(),
                value.state(), value.revision(), value.createdAt().toString());
    }
    /** 按授权版本原子撤销，过期确认不能撤销后续的新授权。 */
    public void revoke(String owner, String id, int revision, Instant now) {
        if (jdbc.update("UPDATE review_grant SET state='REVOKED',revision=revision+1,revoked_at=? WHERE owner_id=? AND id=? AND revision=? AND state='ACTIVE'",
                now.toString(), owner, id, revision) != 1) throw new IllegalArgumentException("授权不存在或已变化，请刷新后重试");
    }
    /** 指纹相同只复用既有不可变快照；金额与状态使用正式列保存。 */
    public String insertSnapshot(FactSnapshot value) {
        jdbc.update("DELETE FROM review_snapshot WHERE owner_id=? AND julianday(expires_at)<=julianday(?)", value.owner(), value.sourceAsOf().toString());
        int inserted = jdbc.update("""
                INSERT INTO review_snapshot(id,owner_id,grant_id,period_from,period_to,timezone,source_as_of,
                    metric_version,fingerprint,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(owner_id,period_from,period_to,timezone,fingerprint) DO NOTHING
                """, value.id(), value.owner(), value.grantId(), value.from().toString(), value.to().toString(), value.timezone(),
                value.sourceAsOf().toString(), value.metricVersion(), value.fingerprint(), value.expiresAt().toString());
        if (inserted == 0) return jdbc.queryForObject("""
                SELECT id FROM review_snapshot WHERE owner_id=? AND period_from=? AND period_to=? AND timezone=? AND fingerprint=?
                """, String.class, value.owner(), value.from().toString(), value.to().toString(), value.timezone(), value.fingerprint());
        for (Fact fact : value.facts()) jdbc.update("INSERT INTO review_fact(snapshot_id,domain,metric_key,subject_id,currency,unit,value) VALUES(?,?,?,?,?,?,?)",
                value.id(), fact.domain(), fact.metricKey(), fact.subjectId(), fact.currency(), fact.unit(), fact.value());
        for (SourceStatus source : value.sources()) jdbc.update("INSERT INTO review_source_status(snapshot_id,domain,state) VALUES(?,?,?)",
                value.id(), source.domain(), source.state());
        return value.id();
    }
    /** 读取未过期快照与事实明细，保留原始采集版本。 */
    public Optional<FactSnapshot> snapshot(String owner, String id, Instant now) {
        return jdbc.query("SELECT * FROM review_snapshot WHERE owner_id=? AND id=? AND julianday(expires_at)>julianday(?)", (rs, row) -> {
            var facts = jdbc.query("SELECT domain,metric_key,subject_id,currency,unit,value FROM review_fact WHERE snapshot_id=? ORDER BY domain,metric_key,subject_id,currency",
                    (r, n) -> new Fact(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getLong(6)), id);
            var sources = jdbc.query("SELECT domain,state FROM review_source_status WHERE snapshot_id=? ORDER BY domain",
                    (r, n) -> new SourceStatus(r.getString(1), r.getString(2)), id);
            return new FactSnapshot(id, owner, rs.getString("grant_id"), java.time.LocalDate.parse(rs.getString("period_from")),
                    java.time.LocalDate.parse(rs.getString("period_to")), rs.getString("timezone"), Instant.parse(rs.getString("source_as_of")),
                    rs.getInt("metric_version"), rs.getString("fingerprint"), Instant.parse(rs.getString("expires_at")), facts, sources);
        }, owner, id, now.toString()).stream().findFirst();
    }
    /** 单次查询轻量元数据；列表不会产生逐快照加载明细的 N+1 查询。 */
    public List<SnapshotHeader> snapshotHeaders(String owner, int limit, Instant now) {
        return jdbc.query("SELECT * FROM review_snapshot WHERE owner_id=? AND julianday(expires_at)>julianday(?) ORDER BY julianday(source_as_of) DESC,id DESC LIMIT ?",
                (rs, row) -> new SnapshotHeader(rs.getString("id"), java.time.LocalDate.parse(rs.getString("period_from")),
                        java.time.LocalDate.parse(rs.getString("period_to")), rs.getString("timezone"),
                        Instant.parse(rs.getString("source_as_of")), rs.getInt("metric_version"),
                        rs.getString("fingerprint"), Instant.parse(rs.getString("expires_at"))), owner, now.toString(), limit);
    }
    /** 显式清除正文；授权审计与模型配置不在清除范围。 */
    public int[] eraseData(String owner) {
        int snapshots = jdbc.update("DELETE FROM review_snapshot WHERE owner_id=?", owner);
        int goals = jdbc.update("DELETE FROM review_goal WHERE owner_id=?", owner);
        return new int[] { snapshots, goals };
    }

    /** 目标总量由领域入口限制为一百条。 */
    public List<Goal> goals(String owner) {
        return jdbc.query("SELECT * FROM review_goal WHERE owner_id=? ORDER BY updated_at DESC,id LIMIT 100", GOAL, owner);
    }
    /** 用于事务内检查有效目标上限，不把归档目标计入。 */
    public int activeGoalCount(String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM review_goal WHERE owner_id=? AND state='ACTIVE'", Integer.class, owner);
    }
    /** 仅查找当前用户目标，其他用户 ID 与不存在目标语义一致。 */
    public Optional<Goal> goal(String owner, String id) {
        return jdbc.query("SELECT * FROM review_goal WHERE owner_id=? AND id=?", GOAL, owner, id).stream().findFirst();
    }
    /** 保存用户已确认的文本，修改必须匹配旧 revision。 */
    public void saveGoal(Goal value, int expectedRevision, boolean create) {
        if (create) {
            jdbc.update("INSERT INTO review_goal(id,owner_id,title,constraint_text,success_criterion,state,revision,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    value.id(), value.owner(), value.title(), value.constraintText(), value.successCriterion(), value.state(),
                    value.revision(), value.createdAt().toString(), value.updatedAt().toString());
        } else if (jdbc.update("UPDATE review_goal SET title=?,constraint_text=?,success_criterion=?,state=?,revision=?,updated_at=? WHERE owner_id=? AND id=? AND revision=?",
                value.title(), value.constraintText(), value.successCriterion(), value.state(), value.revision(),
                value.updatedAt().toString(), value.owner(), value.id(), expectedRevision) != 1)
            throw new IllegalArgumentException("目标已变化，请刷新后重试");
    }
}
