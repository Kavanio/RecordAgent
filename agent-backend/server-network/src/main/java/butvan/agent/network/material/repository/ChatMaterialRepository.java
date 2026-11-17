package butvan.agent.network.material.repository;

import butvan.agent.network.material.model.ChatMaterial;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 对话档案（素材）SQLite 持久化仓库。 */
@Repository
@RequiredArgsConstructor
public class ChatMaterialRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(ChatMaterial material) {
        jdbcTemplate.update("""
                INSERT INTO chat_material (
                    id, owner_id, file_id, channel, session_kind, session_id, project_id,
                    session_title_snapshot, turn_id, category, sub_type, original_name,
                    media_type, size_bytes, sha256, archive_path, created_date, created_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                material.id(),
                material.ownerId(),
                material.fileId(),
                material.channel(),
                material.sessionKind(),
                material.sessionId(),
                material.projectId(),
                material.sessionTitleSnapshot(),
                material.turnId(),
                material.category(),
                material.subType(),
                material.originalName(),
                material.mediaType(),
                material.sizeBytes(),
                material.sha256(),
                material.archivePath(),
                material.createdDate(),
                material.createdAt().toString(),
                material.deletedAt() == null ? null : material.deletedAt().toString()
        );
    }

    public Optional<ChatMaterial> findByFileId(String ownerId, String fileId) {
        return jdbcTemplate.query("""
                SELECT * FROM chat_material
                WHERE owner_id = ? AND file_id = ? AND deleted_at IS NULL
                """, (rs, rowNum) -> map(rs), ownerId, fileId)
                .stream()
                .findFirst();
    }

    public PageResult search(String ownerId, Filter filter, int limit, int offset) {
        StringBuilder where = new StringBuilder(" WHERE owner_id = ? AND deleted_at IS NULL");
        List<Object> params = new ArrayList<>();
        params.add(ownerId);
        appendFilter(where, params, filter);

        Long totalObj = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_material" + where,
                Long.class,
                params.toArray()
        );
        long total = totalObj == null ? 0L : totalObj;

        List<Object> queryParams = new ArrayList<>(params);
        queryParams.add(limit);
        queryParams.add(offset);
        List<ChatMaterial> items = jdbcTemplate.query(
                "SELECT * FROM chat_material" + where + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> map(rs),
                queryParams.toArray()
        );
        return new PageResult(items, total);
    }

    public SummaryResult summarize(String ownerId) {
        Map<String, Object> totals = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS cnt, COALESCE(SUM(size_bytes), 0) AS bytes
                FROM chat_material
                WHERE owner_id = ? AND deleted_at IS NULL
                """, ownerId);
        long totalCount = ((Number) totals.get("cnt")).longValue();
        long totalBytes = ((Number) totals.get("bytes")).longValue();

        Map<String, Long> byCategory = groupCount(ownerId, "category");
        Map<String, Long> byChannel = groupCount(ownerId, "channel");
        Map<String, Long> bySessionKind = groupCount(ownerId, "session_kind");
        Map<String, Long> bySubType = groupCount(ownerId, "sub_type");

        List<SessionBucket> sessions = jdbcTemplate.query("""
                SELECT session_id,
                       MAX(session_title_snapshot) AS title,
                       MAX(channel) AS channel,
                       MAX(session_kind) AS session_kind,
                       COUNT(*) AS cnt,
                       MAX(created_at) AS latest_at
                FROM chat_material
                WHERE owner_id = ? AND deleted_at IS NULL
                GROUP BY session_id
                ORDER BY latest_at DESC
                LIMIT 100
                """, (rs, rowNum) -> new SessionBucket(
                rs.getString("session_id"),
                rs.getString("title"),
                rs.getString("channel"),
                rs.getString("session_kind"),
                rs.getLong("cnt"),
                Instant.parse(rs.getString("latest_at"))
        ), ownerId);

        return new SummaryResult(totalCount, totalBytes, byCategory, byChannel, bySessionKind, bySubType, sessions);
    }

    public int deleteByFileId(String ownerId, String fileId) {
        return jdbcTemplate.update(
                "DELETE FROM chat_material WHERE owner_id = ? AND file_id = ?",
                ownerId, fileId
        );
    }

    private Map<String, Long> groupCount(String ownerId, String column) {
        String sql = "SELECT " + column + " AS k, COUNT(*) AS cnt FROM chat_material "
                + "WHERE owner_id = ? AND deleted_at IS NULL GROUP BY " + column + " ORDER BY cnt DESC";
        Map<String, Long> result = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            result.put(rs.getString("k"), rs.getLong("cnt"));
        }, ownerId);
        return result;
    }

    private void appendFilter(StringBuilder where, List<Object> params, Filter filter) {
        if (filter == null) return;
        if (hasText(filter.category())) {
            where.append(" AND category = ?");
            params.add(filter.category());
        }
        if (hasText(filter.subType())) {
            where.append(" AND sub_type = ?");
            params.add(filter.subType());
        }
        if (hasText(filter.channel())) {
            where.append(" AND channel = ?");
            params.add(filter.channel());
        }
        if (hasText(filter.sessionKind())) {
            where.append(" AND session_kind = ?");
            params.add(filter.sessionKind());
        }
        if (hasText(filter.sessionId())) {
            where.append(" AND session_id = ?");
            params.add(filter.sessionId());
        }
        if (hasText(filter.fromDate())) {
            where.append(" AND created_date >= ?");
            params.add(filter.fromDate());
        }
        if (hasText(filter.toDate())) {
            where.append(" AND created_date <= ?");
            params.add(filter.toDate());
        }
        if (hasText(filter.query())) {
            String like = "%" + filter.query().trim().toLowerCase() + "%";
            where.append(" AND (LOWER(original_name) LIKE ? OR LOWER(session_title_snapshot) LIKE ? "
                    + "OR LOWER(file_id) LIKE ? OR LOWER(archive_path) LIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ChatMaterial map(ResultSet rs) throws SQLException {
        String deletedAt = rs.getString("deleted_at");
        return new ChatMaterial(
                rs.getString("id"),
                rs.getString("owner_id"),
                rs.getString("file_id"),
                rs.getString("channel"),
                rs.getString("session_kind"),
                rs.getString("session_id"),
                rs.getString("project_id"),
                rs.getString("session_title_snapshot"),
                rs.getString("turn_id"),
                rs.getString("category"),
                rs.getString("sub_type"),
                rs.getString("original_name"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("archive_path"),
                rs.getString("created_date"),
                Instant.parse(rs.getString("created_at")),
                deletedAt == null ? null : Instant.parse(deletedAt)
        );
    }

    public record Filter(
            String category,
            String subType,
            String channel,
            String sessionKind,
            String sessionId,
            String fromDate,
            String toDate,
            String query
    ) {
    }

    public record PageResult(List<ChatMaterial> items, long total) {
    }

    public record SessionBucket(
            String sessionId,
            String sessionTitle,
            String channel,
            String sessionKind,
            long count,
            Instant latestCreatedAt
    ) {
    }

    public record SummaryResult(
            long totalCount,
            long totalBytes,
            Map<String, Long> byCategory,
            Map<String, Long> byChannel,
            Map<String, Long> bySessionKind,
            Map<String, Long> bySubType,
            List<SessionBucket> sessions
    ) {
    }
}
