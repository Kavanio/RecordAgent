package butvan.agent.network.usage.repository;

import butvan.agent.network.usage.model.TokenUsageIndexModels.DailyAggregate;
import butvan.agent.network.usage.model.TokenUsageIndexModels.BreakdownAggregate;
import butvan.agent.network.usage.model.TokenUsageIndexModels.InvocationAggregate;
import butvan.agent.network.usage.model.TokenUsageIndexModels.InvocationEntry;
import butvan.agent.network.usage.model.TokenUsageIndexModels.ModelAggregate;
import butvan.agent.network.usage.model.TokenUsageIndexModels.PurposeAggregate;
import butvan.agent.network.usage.model.TokenUsageIndexModels.TurnAggregate;
import butvan.agent.network.usage.model.TokenUsageIndexModels.TurnEntry;
import butvan.agent.network.usage.model.TokenUsageIndexModels.ToolAggregate;
import butvan.agent.network.usage.model.TokenUsageIndexModels.ToolEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.List;

/** Token 用量 SQLite 读模型的替换写入与聚合查询。 */
@Repository
@RequiredArgsConstructor
public class TokenUsageRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /** 出厂重置只删聊天投影；系统用量来源与统计继续保留。 */
    public void resetChat(String owner) {
        jdbcTemplate.update("DELETE FROM token_usage_tool WHERE invocation_row_id IN (SELECT id FROM token_usage_invocation WHERE owner_id=? AND usage_kind='CHAT')", owner);
        jdbcTemplate.update("DELETE FROM token_usage_invocation WHERE owner_id=? AND usage_kind='CHAT'", owner);
        jdbcTemplate.update("DELETE FROM token_usage_turn WHERE owner_id=?", owner);
    }

    /** 用文件源中的完整快照替换当前用户读模型。 */
    public void replace(
            String ownerId,
            List<TurnEntry> turns,
            List<InvocationEntry> invocations,
            List<ToolEntry> tools
    ) {
        // SQLite 外键级联取决于连接级 PRAGMA；显式清理可保证重复重建不会遗留工具投影。
        jdbcTemplate.update("""
                DELETE FROM token_usage_tool
                WHERE invocation_row_id IN (
                    SELECT id FROM token_usage_invocation WHERE owner_id = ?
                )
                """, ownerId);
        jdbcTemplate.update("DELETE FROM token_usage_invocation WHERE owner_id = ?", ownerId);
        jdbcTemplate.update("DELETE FROM token_usage_turn WHERE owner_id = ?", ownerId);
        jdbcTemplate.batchUpdate("""
                INSERT INTO token_usage_turn (
                    message_id, owner_id, session_id, turn_id, occurred_at,
                    input_tokens, output_tokens, cached_input_tokens, total_tokens,
                    model_call_count, reported_call_count, usage_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, turns, turns.size(), (statement, entry) -> {
            statement.setString(1, entry.messageId());
            statement.setString(2, entry.ownerId());
            statement.setString(3, entry.sessionId());
            statement.setString(4, entry.turnId());
            statement.setString(5, entry.occurredAt().toString());
            statement.setLong(6, entry.inputTokens());
            statement.setLong(7, entry.outputTokens());
            statement.setLong(8, entry.cachedInputTokens());
            statement.setLong(9, entry.totalTokens());
            statement.setInt(10, entry.modelCallCount());
            statement.setInt(11, entry.reportedCallCount());
            statement.setString(12, entry.usageStatus());
        });
        jdbcTemplate.batchUpdate("""
                INSERT INTO token_usage_invocation (
                    id, owner_id, session_id, turn_id, message_id, usage_kind, purpose,
                    occurred_at, invocation_id, source, vendor, model, input_tokens,
                    output_tokens, cached_input_tokens, total_tokens, duration_millis, usage_status,
                    model_call_index, token_counter_id, estimated_input_tokens,
                    estimation_delta_tokens, system_prompt_tokens, history_tokens,
                    current_user_tokens, tool_schema_tokens, tool_result_tokens,
                    profile_context_tokens, memory_recall_tokens, rag_context_tokens, other_tokens
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, invocations, invocations.size(), (statement, entry) -> {
            statement.setString(1, entry.id());
            statement.setString(2, entry.ownerId());
            setNullableString(statement, 3, entry.sessionId());
            setNullableString(statement, 4, entry.turnId());
            setNullableString(statement, 5, entry.messageId());
            statement.setString(6, entry.usageKind());
            statement.setString(7, entry.purpose());
            statement.setString(8, entry.occurredAt().toString());
            setNullableString(statement, 9, entry.invocationId());
            setNullableString(statement, 10, entry.source());
            setNullableString(statement, 11, entry.vendor());
            setNullableString(statement, 12, entry.model());
            setNullableLong(statement, 13, entry.inputTokens());
            setNullableLong(statement, 14, entry.outputTokens());
            setNullableLong(statement, 15, entry.cachedInputTokens());
            setNullableLong(statement, 16, entry.totalTokens());
            setNullableLong(statement, 17, entry.durationMillis());
            statement.setString(18, entry.usageStatus());
            statement.setInt(19, entry.modelCallIndex());
            setNullableString(statement, 20, entry.tokenCounterId());
            statement.setLong(21, entry.estimatedInputTokens());
            setNullableLong(statement, 22, entry.estimationDeltaTokens());
            statement.setLong(23, entry.systemPromptTokens());
            statement.setLong(24, entry.historyTokens());
            statement.setLong(25, entry.currentUserTokens());
            statement.setLong(26, entry.toolSchemaTokens());
            statement.setLong(27, entry.toolResultTokens());
            statement.setLong(28, entry.profileContextTokens());
            statement.setLong(29, entry.memoryRecallTokens());
            statement.setLong(30, entry.ragContextTokens());
            statement.setLong(31, entry.otherTokens());
        });
        jdbcTemplate.batchUpdate("""
                INSERT INTO token_usage_tool (
                    invocation_row_id, tool_name, schema_tokens, result_tokens
                ) VALUES (?, ?, ?, ?)
                """, tools, tools.size(), (statement, entry) -> {
            statement.setString(1, entry.invocationRowId());
            statement.setString(2, entry.toolName());
            statement.setLong(3, entry.schemaTokens());
            statement.setLong(4, entry.resultTokens());
        });
    }

    /** 查询调用总量。 */
    public InvocationAggregate summarizeInvocations(QueryScope scope) {
        Query query = invocationQuery(scope, """
                SELECT COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(cached_input_tokens), 0) cached_input_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens,
                       COALESCE(SUM(duration_millis), 0) duration_millis,
                       COUNT(*) model_call_count,
                       SUM(CASE WHEN usage_status = 'COMPLETE' THEN 1 ELSE 0 END) reported_call_count
                FROM token_usage_invocation
                """);
        return namedJdbcTemplate.queryForObject(query.sql(), query.parameters(), (rs, rowNum) ->
                new InvocationAggregate(
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                        rs.getLong("cached_input_tokens"), rs.getLong("total_tokens"),
                        rs.getLong("duration_millis"), rs.getInt("model_call_count"),
                        rs.getInt("reported_call_count")));
    }

    /** 查询聊天轮次数。 */
    public TurnAggregate summarizeTurns(QueryScope scope) {
        Query query = turnQuery(scope, """
                SELECT COUNT(*) turn_count,
                       SUM(CASE WHEN reported_call_count > 0 THEN 1 ELSE 0 END) tracked_turn_count
                FROM token_usage_turn
                """);
        return namedJdbcTemplate.queryForObject(query.sql(), query.parameters(), (rs, rowNum) ->
                new TurnAggregate(rs.getInt("turn_count"), rs.getInt("tracked_turn_count")));
    }

    /** 查询输入 Token 分类合计。 */
    public BreakdownAggregate summarizeBreakdown(QueryScope scope) {
        Query query = invocationQuery(scope, """
                SELECT COALESCE(SUM(estimated_input_tokens), 0) estimated_input_tokens,
                       COALESCE(SUM(system_prompt_tokens), 0) system_prompt_tokens,
                       COALESCE(SUM(history_tokens), 0) history_tokens,
                       COALESCE(SUM(current_user_tokens), 0) current_user_tokens,
                       COALESCE(SUM(tool_schema_tokens), 0) tool_schema_tokens,
                       COALESCE(SUM(tool_result_tokens), 0) tool_result_tokens,
                       COALESCE(SUM(profile_context_tokens), 0) profile_context_tokens,
                       COALESCE(SUM(memory_recall_tokens), 0) memory_recall_tokens,
                       COALESCE(SUM(rag_context_tokens), 0) rag_context_tokens,
                       COALESCE(SUM(other_tokens), 0) other_tokens
                FROM token_usage_invocation
                """);
        return namedJdbcTemplate.queryForObject(query.sql(), query.parameters(), (rs, rowNum) ->
                new BreakdownAggregate(
                        rs.getLong("estimated_input_tokens"), rs.getLong("system_prompt_tokens"),
                        rs.getLong("history_tokens"), rs.getLong("current_user_tokens"),
                        rs.getLong("tool_schema_tokens"), rs.getLong("tool_result_tokens"),
                        rs.getLong("profile_context_tokens"), rs.getLong("memory_recall_tokens"),
                        rs.getLong("rag_context_tokens"), rs.getLong("other_tokens")));
    }

    /** 查询按工具名聚合的 Schema 与 Result Token。 */
    public List<ToolAggregate> summarizeByTool(QueryScope scope) {
        Query query = toolQuery(scope);
        return namedJdbcTemplate.query(query.sql(), query.parameters(), (rs, rowNum) ->
                new ToolAggregate(rs.getString("tool_name"), rs.getLong("schema_tokens"),
                        rs.getLong("result_tokens")));
    }

    /** 按业务用途聚合。 */
    public List<PurposeAggregate> summarizeByPurpose(QueryScope scope) {
        Query query = invocationQuery(scope, """
                SELECT purpose, COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens,
                       COUNT(*) model_call_count,
                       SUM(CASE WHEN usage_status = 'COMPLETE' THEN 1 ELSE 0 END) reported_call_count
                FROM token_usage_invocation
                """, " GROUP BY purpose ORDER BY total_tokens DESC, purpose");
        return namedJdbcTemplate.query(query.sql(), query.parameters(), (rs, rowNum) ->
                new PurposeAggregate(rs.getString("purpose"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), rs.getLong("total_tokens"),
                        rs.getInt("model_call_count"), rs.getInt("reported_call_count")));
    }

    /** 按模型聚合，未知模型使用稳定空字符串返回。 */
    public List<ModelAggregate> summarizeByModel(QueryScope scope) {
        Query query = invocationQuery(scope, """
                SELECT COALESCE(vendor, '') vendor, COALESCE(model, '') model,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens,
                       COUNT(*) model_call_count,
                       SUM(CASE WHEN usage_status = 'COMPLETE' THEN 1 ELSE 0 END) reported_call_count
                FROM token_usage_invocation
                """, " GROUP BY vendor, model ORDER BY total_tokens DESC, vendor, model");
        return namedJdbcTemplate.query(query.sql(), query.parameters(), (rs, rowNum) ->
                new ModelAggregate(rs.getString("vendor"), rs.getString("model"),
                        rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                        rs.getLong("total_tokens"), rs.getInt("model_call_count"),
                        rs.getInt("reported_call_count")));
    }

    /** 按应用所在时区的日期聚合；前端以 ISO 日期稳定展示。 */
    public List<DailyAggregate> summarizeByDay(QueryScope scope) {
        Query query = invocationQuery(scope, """
                SELECT date(occurred_at, 'localtime') usage_date,
                       COALESCE(SUM(input_tokens), 0) input_tokens,
                       COALESCE(SUM(output_tokens), 0) output_tokens,
                       COALESCE(SUM(total_tokens), 0) total_tokens,
                       COUNT(*) model_call_count
                FROM token_usage_invocation
                """, " GROUP BY usage_date ORDER BY usage_date");
        return namedJdbcTemplate.query(query.sql(), query.parameters(), (rs, rowNum) ->
                new DailyAggregate(rs.getString("usage_date"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), rs.getLong("total_tokens"),
                        rs.getInt("model_call_count")));
    }

    private Query invocationQuery(QueryScope scope, String select) {
        return invocationQuery(scope, select, "");
    }

    private Query invocationQuery(QueryScope scope, String select, String suffix) {
        return scopedQuery(scope, select, suffix);
    }

    private Query turnQuery(QueryScope scope, String select) {
        return scopedQuery(scope, select, "");
    }

    private Query scopedQuery(QueryScope scope, String select, String suffix) {
        StringBuilder sql = new StringBuilder(select).append(" WHERE owner_id = :ownerId");
        MapSqlParameterSource parameters = new MapSqlParameterSource("ownerId", scope.ownerId());
        if (scope.fromInclusive() != null) {
            sql.append(" AND occurred_at >= :fromInclusive");
            parameters.addValue("fromInclusive", scope.fromInclusive().toString());
        }
        if (scope.toExclusive() != null) {
            sql.append(" AND occurred_at < :toExclusive");
            parameters.addValue("toExclusive", scope.toExclusive().toString());
        }
        if (scope.sessionId() != null && !scope.sessionId().isBlank()) {
            sql.append(" AND session_id = :sessionId");
            parameters.addValue("sessionId", scope.sessionId());
        }
        sql.append(suffix);
        return new Query(sql.toString(), parameters);
    }

    private Query toolQuery(QueryScope scope) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.tool_name,
                       COALESCE(SUM(t.schema_tokens), 0) schema_tokens,
                       COALESCE(SUM(t.result_tokens), 0) result_tokens
                FROM token_usage_tool t
                JOIN token_usage_invocation i ON i.id = t.invocation_row_id
                WHERE i.owner_id = :ownerId
                """);
        MapSqlParameterSource parameters = new MapSqlParameterSource("ownerId", scope.ownerId());
        if (scope.fromInclusive() != null) {
            sql.append(" AND i.occurred_at >= :fromInclusive");
            parameters.addValue("fromInclusive", scope.fromInclusive().toString());
        }
        if (scope.toExclusive() != null) {
            sql.append(" AND i.occurred_at < :toExclusive");
            parameters.addValue("toExclusive", scope.toExclusive().toString());
        }
        if (scope.sessionId() != null && !scope.sessionId().isBlank()) {
            sql.append(" AND i.session_id = :sessionId");
            parameters.addValue("sessionId", scope.sessionId());
        }
        sql.append(" GROUP BY t.tool_name ORDER BY (schema_tokens + result_tokens) DESC, t.tool_name");
        return new Query(sql.toString(), parameters);
    }

    private static void setNullableString(java.sql.PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableLong(java.sql.PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    /** 聚合查询筛选范围；结束时间为开区间。 */
    public record QueryScope(String ownerId, Instant fromInclusive, Instant toExclusive, String sessionId) {
    }

    private record Query(String sql, MapSqlParameterSource parameters) {
    }
}
