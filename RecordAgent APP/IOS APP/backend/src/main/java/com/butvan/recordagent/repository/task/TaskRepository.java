package com.butvan.recordagent.repository.task;

import com.butvan.recordagent.dto.task.TaskDtos;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 服务端只保存配置和设备观察回执，不用进程定时器模拟手机通知。 */
@Repository
@RequiredArgsConstructor
public class TaskRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<TaskDtos.Task> MAPPER =
            (r, n) ->
                    new TaskDtos.Task(
                            r.getObject("id", UUID.class),
                            r.getString("title"),
                            r.getString("body"),
                            r.getString("recurrence"),
                            r.getInt("hour"),
                            r.getInt("minute"),
                            r.getObject("weekday", Integer.class),
                            r.getObject("day_of_month", Integer.class),
                            r.getObject("once_date", java.time.LocalDate.class),
                            r.getString("time_zone"),
                            r.getBoolean("enabled"),
                            r.getLong("version"));

    public void lockOwner(UUID owner) {
        jdbc.queryForObject(
                "SELECT id FROM record.app_user WHERE id=? FOR UPDATE", UUID.class, owner);
    }

    public Optional<TaskDtos.Task> find(UUID owner, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM record.mobile_task WHERE owner_id=? AND id=? AND deleted_at IS NULL",
                        MAPPER,
                        owner,
                        id)
                .stream()
                .findFirst();
    }

    public List<TaskDtos.Task> page(
            UUID owner, String query, Boolean enabled, int offset, int limit) {
        return jdbc.query(
                "SELECT * FROM record.mobile_task WHERE owner_id=? AND deleted_at IS NULL AND (?::boolean IS NULL OR enabled=?) AND POSITION(lower(?) IN lower(title || ' ' || body))>0 ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                MAPPER,
                owner,
                enabled,
                enabled,
                query,
                limit,
                offset);
    }

    public List<TaskDtos.Task> enabled(UUID owner) {
        return jdbc.query(
                "SELECT * FROM record.mobile_task WHERE owner_id=? AND deleted_at IS NULL AND enabled AND (recurrence<>'once' OR (once_date+make_time(hour,minute,0)) AT TIME ZONE time_zone>now()) ORDER BY created_at,id",
                MAPPER,
                owner);
    }

    public int create(UUID owner, TaskDtos.Save s) {
        return jdbc.update(
                "INSERT INTO record.mobile_task(id,owner_id,title,body,recurrence,hour,minute,weekday,day_of_month,once_date,time_zone,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                s.id(),
                owner,
                s.title().strip(),
                s.body(),
                s.recurrence(),
                s.hour(),
                s.minute(),
                s.weekday(),
                s.dayOfMonth(),
                s.onceDate(),
                s.timeZone(),
                s.enabled());
    }

    public int update(UUID owner, UUID id, TaskDtos.Save s) {
        return jdbc.update(
                "UPDATE record.mobile_task SET title=?,body=?,recurrence=?,hour=?,minute=?,weekday=?,day_of_month=?,once_date=?,time_zone=?,enabled=?,version=version+1,updated_at=now() WHERE owner_id=? AND id=? AND version=? AND deleted_at IS NULL",
                s.title().strip(),
                s.body(),
                s.recurrence(),
                s.hour(),
                s.minute(),
                s.weekday(),
                s.dayOfMonth(),
                s.onceDate(),
                s.timeZone(),
                s.enabled(),
                owner,
                id,
                s.version());
    }

    public int delete(UUID owner, UUID id, long version) {
        return jdbc.update(
                "UPDATE record.mobile_task SET deleted_at=now(),enabled=false,version=version+1,updated_at=now() WHERE owner_id=? AND id=? AND version=? AND deleted_at IS NULL",
                owner,
                id,
                version);
    }

    public void observe(UUID owner, UUID id, TaskDtos.Observation s) {
        jdbc.update(
                "INSERT INTO record.mobile_task_delivery(owner_id,task_id,event_id,delivered_at) VALUES(?,?,?,?) ON CONFLICT(owner_id,task_id,event_id) DO NOTHING",
                owner,
                id,
                s.eventId(),
                s.deliveredAt());
    }

    public List<TaskDtos.Delivery> history(UUID owner, UUID id, int offset, int limit) {
        return jdbc.query(
                "SELECT event_id,delivered_at FROM record.mobile_task_delivery WHERE owner_id=? AND task_id=? ORDER BY delivered_at DESC,event_id DESC LIMIT ? OFFSET ?",
                (r, n) ->
                        new TaskDtos.Delivery(
                                r.getString(1), r.getObject(2, java.time.OffsetDateTime.class)),
                owner,
                id,
                limit,
                offset);
    }
}
