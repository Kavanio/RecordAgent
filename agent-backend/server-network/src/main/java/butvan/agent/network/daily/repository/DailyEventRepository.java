package butvan.agent.network.daily.repository;

import butvan.agent.network.daily.model.DailyEventModels.CalendarItem;
import butvan.agent.network.daily.model.DailyEventModels.DailyDaySummary;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** SQLite 日记录持久化适配器。 */
@Repository
@RequiredArgsConstructor
public class DailyEventRepository {

    /** 日记录公共字段的内部持久化投影。 */
    public record DailyEventRow(
            String id,
            LocalDate eventDate,
            String eventType,
            String title,
            String source,
            String status,
            int version,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** 周期待办定义的内部读取投影。 */
    public record RecurringTodoRow(
            String id,
            String title,
            int version,
            LocalDate effectiveDate,
            String recurrence,
            Integer recurrenceWeekday,
            Integer recurrenceMonthDay,
            boolean completed,
            LocalDate completedDate) {
    }

    private final JdbcTemplate jdbcTemplate;

    /** 插入日记录公共字段。 */
    public void insertEvent(String id, String ownerId, LocalDate date, String type, String title, Instant now) {
        insertEvent(id, ownerId, date, type, title, "manual", null, now);
    }

    /** 插入带来源引用的日记录，供其他领域通过稳定引用同步到日历。 */
    public void insertEvent(String id, String ownerId, LocalDate date, String type, String title,
                            String source, String sourceReference, Instant now) {
        insertEvent(id, ownerId, date, type, title, source, sourceReference, "confirmed", now);
    }

    /** 插入带显式状态的日记录，供具有生命周期的类型使用。 */
    public void insertEvent(String id, String ownerId, LocalDate date, String type, String title,
                            String source, String sourceReference, String status, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO daily_event (
                    id, owner_id, event_date, event_type, title, source, status,
                    source_reference, extension_json, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}', 0, ?, ?)
                """, id, ownerId, date.toString(), type, title, source, status, sourceReference,
                now.toString(), now.toString());
    }

    /** 查询某日全部日记录的公共字段。 */
    public List<DailyEventRow> findDay(String ownerId, LocalDate date) {
        return jdbcTemplate.query("""
                WITH current_param(day_str) AS (SELECT ?)
                SELECT id, event_date, event_type, title, source, status,
                       version, created_at, updated_at
                FROM daily_event e
                JOIN current_param p ON 1=1
                LEFT JOIN todo_detail t ON t.event_id = e.id
                WHERE e.owner_id = ?
                  AND (
                    (e.event_type <> 'todo' AND e.event_date = p.day_str)
                    OR (e.event_type = 'todo' AND (
                      (e.event_date <= p.day_str AND (
                        COALESCE(t.recurrence, 'none') = 'daily'
                        OR (COALESCE(t.recurrence, 'none') = 'none' AND e.event_date = p.day_str)
                        OR (t.recurrence = 'weekly' AND (
                            (t.recurrence_weekday IS NOT NULL AND ((CAST(strftime('%w', p.day_str) AS INTEGER) + 6) % 7) + 1 = t.recurrence_weekday)
                        ))
                        OR (t.recurrence = 'monthly' AND (
                            (t.recurrence_month_day IS NOT NULL AND (
                                CAST(strftime('%d', p.day_str) AS INTEGER) = MIN(t.recurrence_month_day, CAST(strftime('%d', date(p.day_str, 'start of month', '+1 month', '-1 day')) AS INTEGER))
                            ))
                        ))
                      ))
                      OR (t.recurrence = 'weekly' AND t.recurrence_weekday IS NULL
                          AND date(e.event_date, '-' || ((CAST(strftime('%w', e.event_date) AS INTEGER) + 6) % 7) || ' days') <= p.day_str
                          AND EXISTS (
                              SELECT 1 FROM todo_completion c
                              WHERE c.event_id = e.id
                                AND c.period_start = date(p.day_str, '-' || ((CAST(strftime('%w', p.day_str) AS INTEGER) + 6) % 7) || ' days')
                                AND substr(c.completed_at, 1, 10) = p.day_str
                          )
                      )
                      OR (t.recurrence = 'monthly' AND t.recurrence_month_day IS NULL
                          AND date(e.event_date, 'start of month') <= p.day_str
                          AND EXISTS (
                              SELECT 1 FROM todo_completion c
                              WHERE c.event_id = e.id
                                AND c.period_start = date(p.day_str, 'start of month')
                                AND substr(c.completed_at, 1, 10) = p.day_str
                          )
                      )
                    ))
                  )
                ORDER BY created_at ASC, id ASC
                """, (resultSet, rowNumber) -> new DailyEventRow(
                resultSet.getString("id"),
                LocalDate.parse(resultSet.getString("event_date")),
                resultSet.getString("event_type"),
                resultSet.getString("title"),
                resultSet.getString("source"),
                resultSet.getString("status"),
                resultSet.getInt("version"),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))),
                date.toString(), ownerId);
    }

    /** 按所有者读取一条日记录，防止跨用户修改。 */
    public Optional<DailyEventRow> findById(String ownerId, String eventId) {
        List<DailyEventRow> rows = jdbcTemplate.query("""
                SELECT id, event_date, event_type, title, source, status,
                       version, created_at, updated_at
                FROM daily_event
                WHERE owner_id = ? AND id = ?
                """, (resultSet, rowNumber) -> new DailyEventRow(
                resultSet.getString("id"), LocalDate.parse(resultSet.getString("event_date")),
                resultSet.getString("event_type"), resultSet.getString("title"),
                resultSet.getString("source"), resultSet.getString("status"),
                resultSet.getInt("version"), Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))), ownerId, eventId);
        return rows.stream().findFirst();
    }

    /** 读取当前周和当前月内已经生效的周期待办定义。 */
    public List<RecurringTodoRow> findRecurringTodos(
            String ownerId,
            LocalDate weekStart,
            LocalDate weekEnd,
            LocalDate monthStart,
            LocalDate monthEnd) {
        return jdbcTemplate.query("""
                SELECT e.id, e.title, e.version, e.event_date, t.recurrence,
                       t.recurrence_weekday, t.recurrence_month_day,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM todo_completion c
                           WHERE c.event_id = e.id
                             AND c.period_start = CASE t.recurrence
                               WHEN 'weekly' THEN ?
                               WHEN 'monthly' THEN ?
                             END
                       ) THEN 1 ELSE 0 END AS completed,
                       (
                           SELECT substr(c.completed_at, 1, 10)
                           FROM todo_completion c
                           WHERE c.event_id = e.id
                             AND c.period_start = CASE t.recurrence
                               WHEN 'weekly' THEN ?
                               WHEN 'monthly' THEN ?
                             END
                           LIMIT 1
                       ) AS completed_date
                FROM daily_event e
                JOIN todo_detail t ON t.event_id = e.id
                WHERE e.owner_id = ?
                  AND e.event_type = 'todo'
                  AND (
                    (t.recurrence = 'weekly' AND t.recurrence_weekday IS NULL)
                    OR (t.recurrence = 'monthly' AND t.recurrence_month_day IS NULL)
                  )
                  AND e.event_date <= CASE t.recurrence
                    WHEN 'weekly' THEN ?
                    WHEN 'monthly' THEN ?
                  END
                ORDER BY CASE t.recurrence WHEN 'weekly' THEN 0 ELSE 1 END,
                         e.created_at, e.id
                """, (resultSet, rowNumber) -> {
                    String completedDateStr = resultSet.getString("completed_date");
                    LocalDate completedDate = completedDateStr != null && !completedDateStr.isBlank()
                            ? LocalDate.parse(completedDateStr)
                            : null;
                    return new RecurringTodoRow(
                            resultSet.getString("id"),
                            resultSet.getString("title"),
                            resultSet.getInt("version"),
                            LocalDate.parse(resultSet.getString("event_date")),
                            resultSet.getString("recurrence"),
                            nullableInteger(resultSet, "recurrence_weekday"),
                            nullableInteger(resultSet, "recurrence_month_day"),
                            resultSet.getInt("completed") == 1,
                            completedDate);
                },
                weekStart.toString(), monthStart.toString(),
                weekStart.toString(), monthStart.toString(),
                ownerId,
                weekEnd.toString(), monthEnd.toString());
    }

    /** 通过来源领域的稳定引用查找同步日记录。 */
    public Optional<DailyEventRow> findBySourceReference(String ownerId, String source, String sourceReference) {
        List<DailyEventRow> rows = jdbcTemplate.query("""
                SELECT id, event_date, event_type, title, source, status,
                       version, created_at, updated_at
                FROM daily_event
                WHERE owner_id = ? AND source = ? AND source_reference = ?
                """, (resultSet, rowNumber) -> new DailyEventRow(
                resultSet.getString("id"), LocalDate.parse(resultSet.getString("event_date")),
                resultSet.getString("event_type"), resultSet.getString("title"),
                resultSet.getString("source"), resultSet.getString("status"),
                resultSet.getInt("version"), Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at"))), ownerId, source, sourceReference);
        return rows.stream().findFirst();
    }

    /** 以乐观版本推进日记录版本号。 */
    public boolean advanceVersion(String ownerId, String eventId, int expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                UPDATE daily_event
                SET version = version + 1, updated_at = ?
                WHERE owner_id = ? AND id = ? AND version = ?
                """, now.toString(), ownerId, eventId, expectedVersion) == 1;
    }

    /** 修改日记录公共字段并推进乐观版本。 */
    public boolean updateEvent(
            String ownerId, String eventId, int expectedVersion, LocalDate date, String title, Instant now) {
        return jdbcTemplate.update("""
                UPDATE daily_event
                SET event_date = ?, title = ?, version = version + 1, updated_at = ?
                WHERE owner_id = ? AND id = ? AND version = ?
                """, date.toString(), title, now.toString(), ownerId, eventId, expectedVersion) == 1;
    }

    /** 修改日记录公共字段和状态并推进乐观版本。 */
    public boolean updateEvent(
            String ownerId, String eventId, int expectedVersion, LocalDate date, String title,
            String status, Instant now) {
        return jdbcTemplate.update("""
                UPDATE daily_event
                SET event_date = ?, title = ?, status = ?, version = version + 1, updated_at = ?
                WHERE owner_id = ? AND id = ? AND version = ?
                """, date.toString(), title, status, now.toString(), ownerId, eventId, expectedVersion) == 1;
    }

    /** 按所有者和版本删除日记录，详情由外键级联清理。 */
    public boolean delete(String ownerId, String eventId, int expectedVersion) {
        return jdbcTemplate.update(
                "DELETE FROM daily_event WHERE owner_id = ? AND id = ? AND version = ?",
                ownerId, eventId, expectedVersion) == 1;
    }

    /** 查询日期范围内每天的轻量汇总，避免月视图加载手记正文。 */
    public List<DailyDaySummary> findDaySummaries(String ownerId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT e.event_date,
                       COUNT(*) AS event_count,
                       SUM(CASE WHEN e.event_type = 'todo' THEN 1 ELSE 0 END) AS todo_count,
                       SUM(CASE WHEN e.event_type = 'todo' AND EXISTS (
                           SELECT 1 FROM todo_completion c
                           WHERE c.event_id = e.id AND c.period_start = e.event_date
                       ) THEN 1 ELSE 0 END) AS completed_todo_count,
                       SUM(CASE WHEN e.event_type = 'schedule' THEN 1 ELSE 0 END) AS schedule_count,
                       COALESCE(SUM(x.amount_minor), 0) AS expense_total_minor,
                       (SELECT h.title
                        FROM daily_event h
                        WHERE h.owner_id = e.owner_id AND h.event_date = e.event_date
                        ORDER BY h.created_at ASC, h.id ASC
                        LIMIT 1) AS headline
                FROM daily_event e
                LEFT JOIN todo_detail t ON t.event_id = e.id
                LEFT JOIN expense_detail x ON x.event_id = e.id
                WHERE e.owner_id = ? AND e.event_date BETWEEN ? AND ?
                  AND NOT (e.event_type = 'todo' AND COALESCE(t.recurrence, 'none') <> 'none')
                GROUP BY e.event_date
                ORDER BY e.event_date
                """, (resultSet, rowNumber) -> new DailyDaySummary(
                LocalDate.parse(resultSet.getString("event_date")),
                resultSet.getInt("event_count"),
                resultSet.getInt("todo_count"),
                resultSet.getInt("completed_todo_count"),
                resultSet.getInt("schedule_count"),
                BigDecimal.valueOf(resultSet.getLong("expense_total_minor"), 2),
                resultSet.getString("headline")), ownerId, from.toString(), to.toString());
    }

    /** 查询日期范围内周期待办的逐日轻量汇总，不加载其他类型详情。 */
    public List<DailyDaySummary> findRecurringTodoSummaries(
            String ownerId, LocalDate from, LocalDate to, LocalDate today) {
        return jdbcTemplate.query("""
                WITH RECURSIVE dates(event_date) AS (
                    SELECT ?
                    UNION ALL
                    SELECT date(event_date, '+1 day') FROM dates WHERE event_date < ?
                )
                SELECT dates.event_date,
                       COUNT(*) AS todo_count,
                       SUM(CASE WHEN EXISTS (
                           SELECT 1 FROM todo_completion c
                           WHERE c.event_id = e.id
                             AND c.period_start = CASE t.recurrence
                               WHEN 'weekly' THEN date(dates.event_date, '-' || ((CAST(strftime('%w', dates.event_date) AS INTEGER) + 6) % 7) || ' days')
                               WHEN 'monthly' THEN date(dates.event_date, 'start of month')
                               ELSE dates.event_date
                             END
                       ) THEN 1 ELSE 0 END) AS completed_count,
                       MIN(e.title) AS headline
                FROM dates
                JOIN daily_event e ON e.owner_id = ? AND e.event_type = 'todo'
                JOIN todo_detail t ON t.event_id = e.id
                  AND CASE
                    WHEN t.recurrence = 'weekly' AND t.recurrence_weekday IS NULL
                      THEN date(e.event_date, '-' || ((CAST(strftime('%w', e.event_date) AS INTEGER) + 6) % 7) || ' days') <= dates.event_date
                    WHEN t.recurrence = 'monthly' AND t.recurrence_month_day IS NULL
                      THEN date(e.event_date, 'start of month') <= dates.event_date
                    ELSE e.event_date <= dates.event_date
                  END
                  AND (
                    t.recurrence = 'daily'
                    OR (t.recurrence = 'weekly' AND (
                        (t.recurrence_weekday IS NOT NULL AND ((CAST(strftime('%w', dates.event_date) AS INTEGER) + 6) % 7) + 1 = t.recurrence_weekday)
                        OR (t.recurrence_weekday IS NULL AND EXISTS (
                            SELECT 1 FROM todo_completion c
                            WHERE c.event_id = e.id
                              AND c.period_start = date(dates.event_date, '-' || ((CAST(strftime('%w', dates.event_date) AS INTEGER) + 6) % 7) || ' days')
                              AND substr(c.completed_at, 1, 10) = dates.event_date
                        ))
                    ))
                    OR (t.recurrence = 'monthly' AND (
                        (t.recurrence_month_day IS NOT NULL AND (
                            CAST(strftime('%d', dates.event_date) AS INTEGER) = MIN(t.recurrence_month_day, CAST(strftime('%d', date(dates.event_date, 'start of month', '+1 month', '-1 day')) AS INTEGER))
                        ))
                        OR (t.recurrence_month_day IS NULL AND EXISTS (
                            SELECT 1 FROM todo_completion c
                            WHERE c.event_id = e.id
                              AND c.period_start = date(dates.event_date, 'start of month')
                              AND substr(c.completed_at, 1, 10) = dates.event_date
                        ))
                    ))
                  )
                  AND (t.recurrence = 'monthly' OR dates.event_date <= ?)
                GROUP BY dates.event_date
                ORDER BY dates.event_date
                """, (resultSet, rowNumber) -> new DailyDaySummary(
                LocalDate.parse(resultSet.getString("event_date")),
                resultSet.getInt("todo_count"),
                resultSet.getInt("todo_count"),
                resultSet.getInt("completed_count"),
                0,
                BigDecimal.ZERO,
                resultSet.getString("headline")), from.toString(), to.toString(), ownerId, today.toString());
    }

    /** 批量读取每天最多四条事项标题，按日程、待办、学习、手记顺序展示。 */
    public List<CalendarItem> findCalendarItems(
            String ownerId, LocalDate from, LocalDate to, LocalDate today) {
        return jdbcTemplate.query("""
                WITH RECURSIVE dates(day) AS (
                    SELECT ? UNION ALL SELECT date(day, '+1 day') FROM dates WHERE day < ?
                ), ranked AS (
                    SELECT dates.day, e.id, e.event_type, substr(e.title, 1, 80) AS title,
                            ROW_NUMBER() OVER (PARTITION BY dates.day ORDER BY
                               CASE e.event_type WHEN 'schedule' THEN 0 WHEN 'todo' THEN 1
                                   WHEN 'study' THEN 2 WHEN 'journal' THEN 3 ELSE 4 END,
                               e.created_at, e.id) AS position
                    FROM dates
                    JOIN daily_event e ON e.owner_id = ?
                    LEFT JOIN todo_detail t ON t.event_id = e.id
                    WHERE (
                        (e.event_date = dates.day AND NOT (e.event_type = 'todo' AND COALESCE(t.recurrence, 'none') <> 'none'))
                        OR (e.event_type = 'todo'
                            AND CASE
                              WHEN t.recurrence = 'weekly' AND t.recurrence_weekday IS NULL
                                THEN date(e.event_date, '-' || ((CAST(strftime('%w', e.event_date) AS INTEGER) + 6) % 7) || ' days') <= dates.day
                              WHEN t.recurrence = 'monthly' AND t.recurrence_month_day IS NULL
                                THEN date(e.event_date, 'start of month') <= dates.day
                              ELSE e.event_date <= dates.day
                            END
                            AND (dates.day <= ? OR t.recurrence = 'monthly') AND (
                            t.recurrence = 'daily'
                            OR (t.recurrence = 'weekly' AND (
                                (t.recurrence_weekday IS NOT NULL AND ((CAST(strftime('%w', dates.day) AS INTEGER) + 6) % 7) + 1 = t.recurrence_weekday)
                                OR (t.recurrence_weekday IS NULL AND EXISTS (
                                    SELECT 1 FROM todo_completion c
                                    WHERE c.event_id = e.id
                                      AND c.period_start = date(dates.day, '-' || ((CAST(strftime('%w', dates.day) AS INTEGER) + 6) % 7) || ' days')
                                      AND substr(c.completed_at, 1, 10) = dates.day
                                ))
                            ))
                            OR (t.recurrence = 'monthly' AND (
                                (t.recurrence_month_day IS NOT NULL AND (
                                    CAST(strftime('%d', dates.day) AS INTEGER) = MIN(t.recurrence_month_day, CAST(strftime('%d', date(dates.day, 'start of month', '+1 month', '-1 day')) AS INTEGER))
                                ))
                                OR (t.recurrence_month_day IS NULL AND EXISTS (
                                    SELECT 1 FROM todo_completion c
                                    WHERE c.event_id = e.id
                                      AND c.period_start = date(dates.day, 'start of month')
                                      AND substr(c.completed_at, 1, 10) = dates.day
                                ))
                            ))
                        ))
                    )
                )
                SELECT day, id, event_type, title FROM ranked WHERE position <= 4 ORDER BY day, position
                """, (rs, rowNum) -> new CalendarItem(
                LocalDate.parse(rs.getString("day")), rs.getString("id"),
                rs.getString("event_type"), rs.getString("title")),
                from.toString(), to.toString(), ownerId, today.toString());
    }

    private Integer nullableInteger(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

}
