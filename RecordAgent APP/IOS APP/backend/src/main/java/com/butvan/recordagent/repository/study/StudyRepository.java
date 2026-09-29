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
        return jdbc.query("SELECT id,title,started_at,ended_at FROM record.study_session "
                + "WHERE owner_id=? AND ended_at IS NOT NULL AND started_at<? AND ended_at>? "
                + "ORDER BY started_at LIMIT ?", (rs, index) ->
                new Row(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class)),
                owner, end, start, limit);
    }

    /** 累计仅计算已结束时段，未完成时段不产生虚假分钟数。 */
    public long totalMinutes(UUID owner) {
        Long result = jdbc.queryForObject("SELECT COALESCE(SUM(FLOOR(EXTRACT(EPOCH FROM "
                + "(ended_at-started_at))/60)),0) FROM record.study_session "
                + "WHERE owner_id=? AND ended_at IS NOT NULL", Long.class, owner);
        return result == null ? 0 : result;
    }
}
