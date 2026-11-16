package butvan.agent.network.mobiletask.repository;

import butvan.agent.network.mobiletask.model.MobileTaskModels.MobileTask;
import butvan.agent.network.mobiletask.model.MobileTaskModels.MobileTaskDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 手机端通知任务 SQLite 持久化访问。 */
@Repository
@RequiredArgsConstructor
public class MobileTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<MobileTask> findPage(String ownerId, String query, Boolean enabled, int offset, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, title, body, recurrence, hour, minute, weekday, day_of_month,
                       once_date, timezone, enabled, version
                FROM mobile_task
                WHERE owner_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(ownerId);
        if (enabled != null) {
            sql.append(" AND enabled = ?");
            args.add(enabled ? 1 : 0);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (LOWER(title) LIKE ? OR LOWER(body) LIKE ?)");
            String pattern = "%" + query.trim().toLowerCase() + "%";
            args.add(pattern);
            args.add(pattern);
        }
        sql.append(" ORDER BY enabled DESC, updated_at DESC, id ASC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::mapTask, args.toArray());
    }

    public List<MobileTask> findEnabled(String ownerId) {
        return jdbcTemplate.query("""
                SELECT id, title, body, recurrence, hour, minute, weekday, day_of_month,
                       once_date, timezone, enabled, version
                FROM mobile_task
                WHERE owner_id = ? AND enabled = 1
                ORDER BY updated_at DESC, id ASC
                """, this::mapTask, ownerId);
    }

    public Optional<MobileTask> findById(String ownerId, String id) {
        List<MobileTask> rows = jdbcTemplate.query("""
                SELECT id, title, body, recurrence, hour, minute, weekday, day_of_month,
                       once_date, timezone, enabled, version
                FROM mobile_task
                WHERE owner_id = ? AND id = ?
                """, this::mapTask, ownerId, id);
        return rows.stream().findFirst();
    }

    public void insert(String ownerId, MobileTask task, long nowEpochMillis) {
        jdbcTemplate.update("""
                INSERT INTO mobile_task(
                    id, owner_id, title, body, recurrence, hour, minute, weekday,
                    day_of_month, once_date, timezone, enabled, version, created_at, updated_at
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(), ownerId, task.title(), task.body(), task.recurrence(),
                task.hour(), task.minute(), task.weekday(), task.dayOfMonth(),
                task.onceDate(), task.timeZone(), task.enabled() ? 1 : 0,
                task.version(), nowEpochMillis, nowEpochMillis);
    }

    public int update(String ownerId, MobileTask task, long expectedVersion, long nowEpochMillis) {
        return jdbcTemplate.update("""
                UPDATE mobile_task
                SET title = ?, body = ?, recurrence = ?, hour = ?, minute = ?, weekday = ?,
                    day_of_month = ?, once_date = ?, timezone = ?, enabled = ?,
                    version = version + 1, updated_at = ?
                WHERE owner_id = ? AND id = ? AND version = ?
                """,
                task.title(), task.body(), task.recurrence(), task.hour(), task.minute(),
                task.weekday(), task.dayOfMonth(), task.onceDate(), task.timeZone(),
                task.enabled() ? 1 : 0, nowEpochMillis, ownerId, task.id(), expectedVersion);
    }

    public int delete(String ownerId, String id, long expectedVersion) {
        return jdbcTemplate.update("""
                DELETE FROM mobile_task
                WHERE owner_id = ? AND id = ? AND version = ?
                """, ownerId, id, expectedVersion);
    }

    public List<MobileTaskDelivery> findDeliveries(String ownerId, String taskId, int offset, int limit) {
        return jdbcTemplate.query("""
                SELECT id, delivered_at
                FROM mobile_task_delivery
                WHERE owner_id = ? AND task_id = ?
                ORDER BY delivered_at DESC, id ASC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new MobileTaskDelivery(
                        rs.getString("id"),
                        Instant.ofEpochMilli(rs.getLong("delivered_at"))),
                ownerId, taskId, limit, offset);
    }

    public void insertDeliveryIgnoreDuplicate(
            String id, String ownerId, String taskId, String eventId, Instant deliveredAt) {
        jdbcTemplate.update("""
                INSERT INTO mobile_task_delivery(id, task_id, owner_id, event_id, delivered_at)
                VALUES(?, ?, ?, ?, ?)
                ON CONFLICT(owner_id, task_id, event_id) DO NOTHING
                """, id, taskId, ownerId, eventId, deliveredAt.toEpochMilli());
    }

    private MobileTask mapTask(ResultSet rs, int rowNum) throws SQLException {
        Integer weekday = nullableInt(rs, "weekday");
        Integer dayOfMonth = nullableInt(rs, "day_of_month");
        return new MobileTask(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("recurrence"),
                rs.getInt("hour"),
                rs.getInt("minute"),
                weekday,
                dayOfMonth,
                rs.getString("once_date"),
                rs.getString("timezone"),
                rs.getInt("enabled") == 1,
                rs.getLong("version"));
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int val = rs.getInt(column);
        return rs.wasNull() ? null : val;
    }
}
