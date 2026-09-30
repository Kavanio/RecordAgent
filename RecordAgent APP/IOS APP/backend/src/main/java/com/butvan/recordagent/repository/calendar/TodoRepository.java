package com.butvan.recordagent.repository.calendar;

import com.butvan.recordagent.dto.calendar.CalendarDtos;
import com.butvan.recordagent.repository.mapper.projection.DailySummaryMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 以 owner_id 约束每次待办查询和写入。 */
@Repository
@RequiredArgsConstructor
public class TodoRepository {
    private final JdbcTemplate jdbc;

    public record Row(
            UUID id,
            String title,
            String detail,
            LocalDate date,
            LocalTime time,
            String recurrence,
            long version) {}

    public void create(
            UUID id,
            UUID owner,
            String title,
            String detail,
            LocalDate date,
            LocalTime time,
            String timeZone,
            String recurrence) {
        jdbc.update(
                "INSERT INTO record.calendar_entry(id,owner_id,entry_type,event_date,title,detail) "
                        + "VALUES(?,?,'todo',?,?,?)",
                id,
                owner,
                date,
                title,
                detail);
        jdbc.update(
                "INSERT INTO record.calendar_todo(entry_id,todo_time,timezone,recurrence,recurrence_weekday,recurrence_month_day) "
                        + "VALUES(?,?,?,?,?,?)",
                id,
                time,
                timeZone,
                recurrence,
                recurrence.equals("weekly") ? 7 : null,
                recurrence.equals("monthly") ? 31 : null);
    }

    /** 周期发生规则和完成状态在同一 SQL 中计算；日期窗口由服务端限制。 */
    private static final String OCCURRENCES =
            """
            FROM generate_series(?::date,?::date,interval '1 day') d(day)
            JOIN record.calendar_entry e ON e.owner_id=? AND e.event_date<=d.day::date
            JOIN record.calendar_todo t ON t.entry_id=e.id
            LEFT JOIN record.todo_completion c ON c.entry_id=e.id AND c.period_start=CASE t.recurrence
              WHEN 'weekly' THEN d.day::date-(extract(isodow FROM d.day)::int-1)
              WHEN 'monthly' THEN date_trunc('month',d.day)::date ELSE d.day::date END
            WHERE e.status='confirmed' AND (
              (t.recurrence='none' AND e.event_date=d.day::date) OR t.recurrence='daily'
              OR (t.recurrence='weekly' AND extract(isodow FROM d.day)=7)
              OR (t.recurrence='monthly' AND d.day::date=(date_trunc('month',d.day)+interval '1 month - 1 day')::date))
            """;

    /** 一次查询取得当日实例和全部完成状态；多读一条由服务层判断超限。 */
    public List<CalendarDtos.Todo> day(UUID owner, LocalDate date, int limit) {
        return day(owner, date, limit, 0);
    }

    /** 本人当日待办分页，保留相同发生日及完成规则。 */
    public List<CalendarDtos.Todo> day(UUID owner, LocalDate date, int limit, int offset) {
        return jdbc.query(
                "SELECT e.id,e.title,e.detail,d.day::date,t.todo_time,t.recurrence,c.entry_id IS NOT NULL,e.version "
                        + OCCURRENCES
                        + " ORDER BY e.event_date,e.created_at,e.id LIMIT ? OFFSET ?",
                (rs, n) ->
                        new CalendarDtos.Todo(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getObject(4, LocalDate.class),
                                rs.getObject(5, LocalTime.class),
                                rs.getString(6),
                                rs.getBoolean(7),
                                rs.getLong(8)),
                date,
                date,
                owner,
                limit,
                offset);
    }

    /** 月摘要内部携带完成数量，供总览复用同一周期口径。 */
    public record MonthRow(LocalDate date, int count, int completed, List<String> titles) {}

    /** 所有实例计数，不读取正文，也不受明细返回上限影响。 */
    public List<MonthRow> month(UUID owner, LocalDate start, LocalDate endInclusive) {
        return jdbc.query(
                "SELECT d.day::date AS day,count(*) AS count,count(c.entry_id) AS completed,"
                        + "(array_agg(e.title ORDER BY e.event_date,e.created_at,e.id))[1:3] AS titles "
                        + OCCURRENCES
                        + " GROUP BY d.day ORDER BY d.day",
                (rs, n) ->
                        new MonthRow(
                                rs.getObject("day", LocalDate.class),
                                rs.getInt("count"),
                                rs.getInt("completed"),
                                DailySummaryMapper.titles(rs)),
                start,
                endInclusive,
                owner);
    }

    /** 锁定本人待办，版本检查与完成写入必须在同一事务中执行。 */
    public Optional<Row> owned(UUID owner, UUID id) {
        List<Row> rows =
                jdbc.query(
                        "SELECT e.id,e.title,e.detail,e.event_date,t.todo_time,t.recurrence,e.version "
                                + "FROM record.calendar_entry e JOIN record.calendar_todo t ON t.entry_id=e.id "
                                + "WHERE e.owner_id=? AND e.id=? AND e.status='confirmed' FOR UPDATE OF e",
                        (rs, n) ->
                                new Row(
                                        rs.getObject(1, UUID.class),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getObject(4, LocalDate.class),
                                        rs.getObject(5, LocalTime.class),
                                        rs.getString(6),
                                        rs.getLong(7)),
                        owner,
                        id);
        return rows.stream().findFirst();
    }

    public void setCompleted(UUID id, LocalDate period, boolean completed) {
        if (completed) {
            jdbc.update(
                    "INSERT INTO record.todo_completion(entry_id,period_start) VALUES(?,?) ON CONFLICT DO NOTHING",
                    id,
                    period);
        } else {
            jdbc.update(
                    "DELETE FROM record.todo_completion WHERE entry_id=? AND period_start=?",
                    id,
                    period);
        }
    }

    public void incrementVersion(UUID id) {
        jdbc.update(
                "UPDATE record.calendar_entry SET version=version+1,updated_at=now() WHERE id=?",
                id);
    }
}
