package com.butvan.recordagent.repository.study;

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
    public List<Row> overlapping(UUID owner, OffsetDateTime start, OffsetDateTime end, int limit, int offset) {
        return jdbc.query("SELECT id,title,started_at,ended_at FROM record.study_session "
                + "WHERE owner_id=? AND ended_at IS NOT NULL AND started_at<? AND ended_at>? "
                + "ORDER BY started_at,id LIMIT ? OFFSET ?", (rs, index) ->
                new Row(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class)),
                owner, end, start, limit, offset);
    }

    /** 累计仅计算已结束时段，未完成时段不产生虚假分钟数。 */
    public long totalMinutes(UUID owner) {
        Long result = jdbc.queryForObject("SELECT COALESCE(SUM(FLOOR(EXTRACT(EPOCH FROM "
                + "(ended_at-started_at))/60)),0) FROM record.study_session "
                + "WHERE owner_id=? AND ended_at IS NOT NULL", Long.class, owner);
        return result == null ? 0 : result;
    }
    /** 按请求时区生成自然日，计数与标题由 SQL 聚合，跨日时段每天仅计一次。 */
    public List<com.butvan.recordagent.service.query.DailySummary> monthSummary(UUID owner, java.time.LocalDate start,
            java.time.LocalDate end, String zone) {
        return jdbc.query("""
                SELECT d.day::date AS day,count(*) AS count,
                       (array_agg(s.title ORDER BY s.started_at,s.id))[1:3] AS titles
                FROM generate_series(?::date,(?::date - 1),interval '1 day') d(day)
                JOIN record.study_session s ON s.owner_id=? AND s.ended_at IS NOT NULL
                 AND s.started_at < ((d.day::date + 1)::timestamp AT TIME ZONE ?)
                 AND s.ended_at > (d.day::timestamp AT TIME ZONE ?)
                GROUP BY d.day ORDER BY d.day
                """, (rs, n) -> com.butvan.recordagent.repository.mapper.projection.DailySummaryMapper.map(rs, "focus"), start, end, owner, zone, zone);
    }

    /** 与日明细相同的区间裁剪及逐时段取整口径，覆盖全部已完成时段。 */
    public long minutesBetween(UUID owner, OffsetDateTime start, OffsetDateTime end) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(FLOOR(EXTRACT(EPOCH FROM
                       (LEAST(ended_at,?::timestamptz)-GREATEST(started_at,?::timestamptz)))/60)),0)
                FROM record.study_session WHERE owner_id=? AND ended_at IS NOT NULL AND started_at<? AND ended_at>?
                """, Long.class, end, start, owner, end, start);
    }

}
