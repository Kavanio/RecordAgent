package com.butvan.recordagent.repository.record;

import com.butvan.recordagent.dto.record.JournalDtos;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 资料表中 journal 类型的受控访问。 */
@Repository
@RequiredArgsConstructor
public class JournalRepository {
    private final JdbcTemplate jdbc;

    /** 保存纯文本手记；正文不进入日志。 */
    public void create(UUID id, UUID owner, LocalDate date, String title, String body) {
        jdbc.update("INSERT INTO record.record_entry(id,owner_id,record_date,record_type,title,content_text) "
                + "VALUES(?,?,?,'journal',?,?)", id, owner, date, title, body);
    }

    /** 用用户 ID、类型和有界日期范围隔离读取。 */
    public List<JournalDtos.Journal> between(UUID owner, LocalDate start, LocalDate end, int limit) {
        return between(owner, start, end, limit, 0);
    }

    /** 使用稳定排序分页读取本人明细。 */
    public List<JournalDtos.Journal> between(UUID owner, LocalDate start, LocalDate end, int limit, int offset) {
        return jdbc.query("SELECT id,record_date,COALESCE(title,''),content_text,version FROM record.record_entry "
                + "WHERE owner_id=? AND record_type='journal' AND trashed_at IS NULL "
                + "AND record_date>=? AND record_date<? ORDER BY record_date,created_at,id LIMIT ?",
                (rs, index) -> new JournalDtos.Journal(rs.getObject(1, UUID.class),
                        rs.getObject(2, LocalDate.class), rs.getString(3), rs.getString(4), rs.getLong(5)),
                owner, start, end, limit, offset);
    }

    /** 个人页累计资料统计，排除回收站。 */
    public com.butvan.recordagent.service.record.JournalService.Summary summary(UUID owner) {
        return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(length(content_text)),0) "
                + "FROM record.record_entry WHERE owner_id=? AND trashed_at IS NULL",
                (rs, index) -> new com.butvan.recordagent.service.record.JournalService.Summary(rs.getLong(1), rs.getLong(2)), owner);
    }
    /** 月历只读取全部计数及最多三条标题，不加载正文。 */
    public List<com.butvan.recordagent.service.query.DailySummary> monthSummary(UUID owner, LocalDate start, LocalDate end) {
        return jdbc.query("""
                SELECT record_date AS day, count(*) AS count, (array_agg(COALESCE(title,'') ORDER BY created_at,id))[1:3] AS titles FROM record.record_entry WHERE owner_id=? AND record_type='journal' AND trashed_at IS NULL AND record_date>=? AND record_date<? GROUP BY record_date ORDER BY record_date
                """, (rs, n) -> com.butvan.recordagent.repository.mapper.projection.DailySummaryMapper.map(rs, "journal"), owner, start, end);
    }

}
