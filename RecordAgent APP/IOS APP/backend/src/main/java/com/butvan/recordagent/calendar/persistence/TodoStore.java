package com.butvan.recordagent.calendar.persistence;

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
public class TodoStore {
    private final JdbcTemplate jdbc;

    public record Row(UUID id, String title, LocalDate date, LocalTime time,
            String recurrence, long version) {}

    public void create(UUID id, UUID owner, String title, String detail, LocalDate date,
            LocalTime time, String timeZone, String recurrence) {
        jdbc.update("INSERT INTO record.calendar_entry(id,owner_id,entry_type,event_date,title,detail) "
                + "VALUES(?,?,'todo',?,?,?)", id, owner, date, title, detail);
        jdbc.update("INSERT INTO record.calendar_todo(entry_id,todo_time,timezone,recurrence,recurrence_weekday,recurrence_month_day) "
                + "VALUES(?,?,?,?,?,?)", id, time, timeZone, recurrence,
                recurrence.equals("weekly") ? date.getDayOfWeek().getValue() : null,
                recurrence.equals("monthly") ? 31 : null);
    }

    /** 扫描目标日期以前的候选周期规则；读取范围受 owner 和日期限制。 */
    public List<Row> candidates(UUID owner, LocalDate date) {
        return jdbc.query("SELECT e.id,e.title,e.event_date,t.todo_time,t.recurrence,e.version "
                + "FROM record.calendar_entry e JOIN record.calendar_todo t ON t.entry_id=e.id "
                + "WHERE e.owner_id=? AND e.status='confirmed' AND e.event_date<=? "
                + "ORDER BY e.event_date,e.created_at LIMIT 5000", (rs, n) ->
                new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalTime.class), rs.getString(5), rs.getLong(6)), owner, date);
    }

    public Optional<Row> owned(UUID owner, UUID id) {
        List<Row> rows = jdbc.query("SELECT e.id,e.title,e.event_date,t.todo_time,t.recurrence,e.version "
                + "FROM record.calendar_entry e JOIN record.calendar_todo t ON t.entry_id=e.id "
                + "WHERE e.owner_id=? AND e.id=? AND e.status='confirmed' FOR UPDATE OF e", (rs, n) ->
                new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalTime.class), rs.getString(5), rs.getLong(6)), owner, id);
        return rows.stream().findFirst();
    }

    public boolean completed(UUID id, LocalDate period) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM record.todo_completion WHERE entry_id=? AND period_start=?",
                Integer.class, id, period);
        return count != null && count > 0;
    }

    public void setCompleted(UUID id, LocalDate period, boolean completed) {
        if (completed) {
            jdbc.update("INSERT INTO record.todo_completion(entry_id,period_start) VALUES(?,?) ON CONFLICT DO NOTHING",
                    id, period);
        } else {
            jdbc.update("DELETE FROM record.todo_completion WHERE entry_id=? AND period_start=?", id, period);
        }
    }

    public void incrementVersion(UUID id) {
        jdbc.update("UPDATE record.calendar_entry SET version=version+1,updated_at=now() WHERE id=?", id);
    }
}
