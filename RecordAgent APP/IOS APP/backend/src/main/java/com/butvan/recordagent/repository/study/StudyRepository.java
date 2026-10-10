package com.butvan.recordagent.repository.study;

import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.study.StudyDtos;
import com.butvan.recordagent.repository.mapper.projection.DailySummaryMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 学习时段的本人查询与累计统计。 */
@Repository
@RequiredArgsConstructor
public class StudyRepository {
    private final JdbcTemplate jdbc;

    public record Row(UUID id, String title, OffsetDateTime start, OffsetDateTime end) {}

    /** 用重叠区间查询，支持跨午夜的时段。 */
    public List<Row> overlapping(UUID owner, OffsetDateTime start, OffsetDateTime end, int limit) {
        return overlapping(owner, start, end, limit, 0);
    }

    /** 按稳定开始时间和 ID 分页读取本人重叠时段。 */
    public List<Row> overlapping(
            UUID owner, OffsetDateTime start, OffsetDateTime end, int limit, int offset) {
        return jdbc.query(
                "SELECT id,title,started_at,ended_at FROM record.study_session "
                        + "WHERE owner_id=? AND ended_at IS NOT NULL AND started_at<? AND ended_at>? "
                        + "ORDER BY started_at,id LIMIT ? OFFSET ?",
                (rs, index) ->
                        new Row(
                                rs.getObject(1, UUID.class), rs.getString(2),
                                rs.getObject(3, OffsetDateTime.class),
                                        rs.getObject(4, OffsetDateTime.class)),
                owner,
                end,
                start,
                limit,
                offset);
    }

    /** 累计仅计算已结束时段，未完成时段不产生虚假分钟数。 */
    public long totalMinutes(UUID owner) {
        Long result =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(FLOOR(EXTRACT(EPOCH FROM "
                                + "(ended_at-started_at))/60)),0) FROM record.study_session "
                                + "WHERE owner_id=? AND ended_at IS NOT NULL",
                        Long.class,
                        owner);
        return result == null ? 0 : result;
    }

    /** 按请求时区生成自然日，计数与标题由 SQL 聚合，跨日时段每天仅计一次。 */
    public List<DailySummary> monthSummary(
            UUID owner, LocalDate start, LocalDate end, String zone) {
        return jdbc.query(
                """
                SELECT d.day::date AS day,count(*) AS count,
                       (array_agg(s.title ORDER BY s.started_at,s.id))[1:3] AS titles
                FROM generate_series(?::date,(?::date - 1),interval '1 day') d(day)
                JOIN record.study_session s ON s.owner_id=? AND s.ended_at IS NOT NULL
                 AND s.started_at < ((d.day::date + 1)::timestamp AT TIME ZONE ?)
                 AND s.ended_at > (d.day::timestamp AT TIME ZONE ?)
                GROUP BY d.day ORDER BY d.day
                """,
                (rs, n) -> DailySummaryMapper.map(rs, "focus"),
                start,
                end,
                owner,
                zone,
                zone);
    }

    /** 与日明细相同的区间裁剪及逐时段取整口径，覆盖全部已完成时段。 */
    public long minutesBetween(UUID owner, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(FLOOR(EXTRACT(EPOCH FROM
                       (LEAST(ended_at,?::timestamptz)-GREATEST(started_at,?::timestamptz)))/60)),0)
                FROM record.study_session WHERE owner_id=? AND ended_at IS NOT NULL AND started_at<? AND ended_at>?
                """,
                Long.class,
                end,
                start,
                owner,
                end,
                start);
    }

    private static final org.springframework.jdbc.core.RowMapper<StudyDtos.Entry> ENTRY =
            (r, n) ->
                    new StudyDtos.Entry(
                            r.getObject("id", UUID.class),
                            r.getString("title"),
                            r.getObject("started_at", OffsetDateTime.class),
                            r.getObject("ended_at", OffsetDateTime.class),
                            r.getString("timezone"),
                            r.getLong("version"));

    /** 用户行锁串行化开始操作，避免同账户有两个活跃计时。 */
    public void lockOwner(UUID owner) {
        jdbc.queryForObject(
                "SELECT id FROM record.app_user WHERE id=? FOR UPDATE", UUID.class, owner);
    }

    public java.util.Optional<StudyDtos.Entry> find(UUID owner, UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM record.study_session WHERE owner_id=? AND id=?",
                        ENTRY,
                        owner,
                        id)
                .stream()
                .findFirst();
    }

    public java.util.Optional<StudyDtos.Entry> active(UUID owner) {
        return jdbc
                .query(
                        "SELECT * FROM record.study_session WHERE owner_id=? AND ended_at IS NULL",
                        ENTRY,
                        owner)
                .stream()
                .findFirst();
    }

    public void create(UUID owner, StudyDtos.Save s) {
        jdbc.update(
                "INSERT INTO record.study_session(id,owner_id,title,started_at,ended_at,timezone,source) VALUES(?,?,?,?,?,?,?)",
                s.id(),
                owner,
                s.title().strip(),
                s.startedAt(),
                s.endedAt(),
                s.timeZone(),
                s.endedAt() == null ? "timer" : "manual");
    }

    public int finish(UUID owner, UUID id, long version, OffsetDateTime end) {
        return jdbc.update(
                "UPDATE record.study_session SET ended_at=?,version=version+1,updated_at=now() WHERE owner_id=? AND id=? AND version=? AND ended_at IS NULL",
                end,
                owner,
                id,
                version);
    }

    public int delete(UUID owner, UUID id, long version) {
        return jdbc.update(
                "DELETE FROM record.study_session WHERE owner_id=? AND id=? AND version=?",
                owner,
                id,
                version);
    }

    public List<StudyDtos.Entry> entries(
            UUID owner,
            OffsetDateTime start,
            OffsetDateTime end,
            String query,
            int offset,
            int limit) {
        return jdbc.query(
                "SELECT * FROM record.study_session WHERE owner_id=? AND ended_at IS NOT NULL AND started_at<? AND ended_at>? AND POSITION(lower(?) IN lower(title))>0 ORDER BY started_at DESC,id DESC LIMIT ? OFFSET ?",
                ENTRY,
                owner,
                end,
                start,
                query,
                limit,
                offset);
    }

    public StudyDtos.Overview overview(UUID owner, LocalDate start, LocalDate end, String zone) {
        long minutes =
                minutesBetween(
                        owner,
                        start.atStartOfDay(java.time.ZoneId.of(zone)).toOffsetDateTime(),
                        end.atStartOfDay(java.time.ZoneId.of(zone)).toOffsetDateTime());
        return jdbc.queryForObject(
                """
  SELECT count(*),(SELECT count(DISTINCT d.day) FROM generate_series(?::date,(?::date-1),interval '1 day') d(day)
  JOIN record.study_session s ON s.owner_id=? AND s.ended_at IS NOT NULL AND s.started_at<((d.day::date+1)::timestamp AT TIME ZONE ?) AND s.ended_at>(d.day::timestamp AT TIME ZONE ?))
  FROM record.study_session WHERE owner_id=? AND ended_at IS NOT NULL AND started_at<? AND ended_at>?
  """,
                (r, n) -> new StudyDtos.Overview(minutes, r.getLong(1), r.getLong(2)),
                start,
                end,
                owner,
                zone,
                zone,
                owner,
                end.atStartOfDay(java.time.ZoneId.of(zone)).toOffsetDateTime(),
                start.atStartOfDay(java.time.ZoneId.of(zone)).toOffsetDateTime());
    }
}
