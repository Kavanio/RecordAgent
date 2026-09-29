package com.butvan.recordagent.repository.calendar;

import com.butvan.recordagent.dto.calendar.ScheduleDtos;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 日程表的所有权约束与 SQL 访问。 */
@Repository
@RequiredArgsConstructor
public class ScheduleRepository {
    private final JdbcTemplate jdbc;

    /** 在同一事务内写入基础条目和日程字段。 */
    public void create(UUID id, UUID owner, String title, String detail, LocalDate date,
            LocalTime start, LocalTime end, String zone) {
        jdbc.update("INSERT INTO record.calendar_entry(id,owner_id,entry_type,event_date,title,detail) "
                + "VALUES(?,?,'schedule',?,?,?)", id, owner, date, title, detail);
        jdbc.update("INSERT INTO record.calendar_schedule(entry_id,start_time,end_time,timezone) "
                + "VALUES(?,?,?,?)", id, start, end, zone);
    }

    /** 限定用户与日期范围，避免跨账户或无界读取。 */
    public List<ScheduleDtos.Schedule> between(UUID owner, LocalDate start, LocalDate end, int limit) {
        return jdbc.query("SELECT e.id,e.title,e.detail,e.event_date,s.start_time,s.end_time,s.timezone,e.version "
                + "FROM record.calendar_entry e JOIN record.calendar_schedule s ON s.entry_id=e.id "
                + "WHERE e.owner_id=? AND e.status='confirmed' AND e.event_date>=? AND e.event_date<? "
                + "ORDER BY e.event_date,s.start_time,e.created_at LIMIT ?", (rs, index) ->
                new ScheduleDtos.Schedule(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalTime.class),
                        rs.getObject(6, LocalTime.class), rs.getString(7), rs.getLong(8)),
                owner, start, end, limit);
    }
}
