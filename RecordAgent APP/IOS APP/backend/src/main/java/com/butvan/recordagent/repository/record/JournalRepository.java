package com.butvan.recordagent.repository.record;

import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.record.JournalDtos;
import com.butvan.recordagent.repository.mapper.projection.DailySummaryMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 手记写入与全部资料的日历投影；读取保留用户、日期和回收站边界。 */
@Repository
@RequiredArgsConstructor
public class JournalRepository {
    private final JdbcTemplate jdbc;

    /** 保存纯文本手记；正文不进入日志。 */
    public void create(UUID id, UUID owner, LocalDate date, String title, String body) {
        jdbc.update(
                "INSERT INTO record.record_entry(id,owner_id,record_date,record_type,title,content_text) "
                        + "VALUES(?,?,?,'journal',?,?)",
                id,
                owner,
                date,
                title,
                body);
    }

    /** 用用户 ID 和有界日期范围读取全部资料类型。 */
    public List<JournalDtos.Journal> between(
            UUID owner, LocalDate start, LocalDate end, int limit) {
        return between(owner, start, end, limit, 0);
    }

    /** 使用稳定排序分页读取本人明细。 */
    public List<JournalDtos.Journal> between(
            UUID owner, LocalDate start, LocalDate end, int limit, int offset) {
        return jdbc.query(
                "SELECT id,record_date,COALESCE(title,''),content_text,version,created_at,updated_at FROM record.record_entry "
                        + "WHERE owner_id=? AND trashed_at IS NULL "
                        + "AND record_date>=? AND record_date<? ORDER BY record_date,created_at,id LIMIT ? OFFSET ?",
                (rs, index) ->
                        new JournalDtos.Journal(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, LocalDate.class),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getLong(5),
                                rs.getObject(6, java.time.OffsetDateTime.class).toInstant(),
                                rs.getObject(7, java.time.OffsetDateTime.class).toInstant()),
                owner,
                start,
                end,
                limit,
                offset);
    }

    /** 持久层统计快照，不依赖 Service 接口。 */
    public record SummaryRow(long documentCount, long characterCount) {}

    /** 个人页累计资料统计，排除回收站。 */
    public SummaryRow summary(UUID owner) {
        return jdbc.queryForObject(
                "SELECT COUNT(*),COALESCE(SUM(length(content_text)),0) "
                        + "FROM record.record_entry WHERE owner_id=? AND trashed_at IS NULL",
                (rs, index) -> new SummaryRow(rs.getLong(1), rs.getLong(2)),
                owner);
    }

    /** 月历只读取全部计数及最多三条标题，不加载正文。 */
    public List<DailySummary> monthSummary(UUID owner, LocalDate start, LocalDate end) {
        return jdbc.query(
                """
                SELECT record_date AS day, count(*) AS count, (array_agg(COALESCE(title,'') ORDER BY created_at,id))[1:3] AS titles FROM record.record_entry WHERE owner_id=? AND trashed_at IS NULL AND record_date>=? AND record_date<? GROUP BY record_date ORDER BY record_date
                """,
                (rs, n) -> DailySummaryMapper.map(rs, "journal"),
                owner,
                start,
                end);
    }
}
