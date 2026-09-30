package com.butvan.recordagent.repository.mapper.projection;

import com.butvan.recordagent.service.query.DailySummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** 聚合查询的日期、数量与标题映射；SQL 使用统一别名，数组资源及时释放。 */
public final class DailySummaryMapper {
    private DailySummaryMapper() {}

    /** 映射不含正文的领域月摘要。 */
    public static DailySummary map(ResultSet rs, String kind) throws SQLException {
        return new DailySummary(rs.getObject("day", LocalDate.class), rs.getInt("count"), titles(rs), kind);
    }

    /** PostgreSQL 标题数组最多三项。 */
    public static List<String> titles(ResultSet rs) throws SQLException {
        var array = rs.getArray("titles");
        if (array == null) return List.of();
        try { return List.copyOf(Arrays.asList((String[]) array.getArray())); }
        finally { array.free(); }
    }
}
