package com.butvan.recordagent.repository.finance;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.repository.mapper.projection.DailySummaryMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 收支表的用户隔离、账户锁和有界读取。 */
@Repository
@RequiredArgsConstructor
public class FinanceRepository {
    private final JdbcTemplate jdbc;

    public record LockedAccount(UUID id, long balance, long version) {}

    /** 期初余额是账户初始状态，不伪造一笔普通收入。 */
    public void createAccount(UUID id, UUID owner, String name, long balance) {
        jdbc.update(
                "INSERT INTO record.finance_account(id,owner_id,name,account_type,currency,balance_minor) "
                        + "VALUES(?,?,?,'cash','CNY',?)",
                id,
                owner,
                name,
                balance);
    }

    /** 账户列表仅返回当前用户。 */
    public List<FinanceDtos.Account> accounts(UUID owner, int limit) {
        return accounts(owner, limit, 0);
    }

    /** 账户列表分页，按创建时间和稳定 ID 排序。 */
    public List<FinanceDtos.Account> accounts(UUID owner, int limit, int offset) {
        return jdbc.query(
                "SELECT id,name,balance_minor,version FROM record.finance_account "
                        + "WHERE owner_id=? ORDER BY created_at,id LIMIT ? OFFSET ?",
                (rs, index) ->
                        new FinanceDtos.Account(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getLong(3),
                                rs.getLong(4)),
                owner,
                limit,
                offset);
    }

    /** 写入前锁定本人账户，避免并发支出绕过余额约束。 */
    public Optional<LockedAccount> lockAccount(UUID owner, UUID id) {
        return jdbc
                .query(
                        "SELECT id,balance_minor,version FROM record.finance_account "
                                + "WHERE owner_id=? AND id=? FOR UPDATE",
                        (rs, index) ->
                                new LockedAccount(
                                        rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)),
                        owner,
                        id)
                .stream()
                .findFirst();
    }

    /** 在账户锁保护下原子更新余额和版本。 */
    public void updateBalance(UUID owner, UUID account, long balance) {
        jdbc.update(
                "UPDATE record.finance_account SET balance_minor=?,version=version+1,updated_at=now() "
                        + "WHERE owner_id=? AND id=?",
                balance,
                owner,
                account);
    }

    /** 分类名称属于同一用户与收支类型，不跨账户复用其他人的分类。 */
    public UUID category(UUID owner, String type, String name) {
        UUID candidate = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO record.finance_category(id,owner_id,transaction_type,name) "
                        + "VALUES(?,?,?,?) ON CONFLICT(owner_id,transaction_type,name) DO NOTHING",
                candidate,
                owner,
                type,
                name);
        return jdbc.queryForObject(
                "SELECT id FROM record.finance_category "
                        + "WHERE owner_id=? AND transaction_type=? AND name=?",
                UUID.class,
                owner,
                type,
                name);
    }

    /** 与账户余额更新处于同一事务。 */
    public void createTransaction(
            UUID id,
            UUID owner,
            UUID account,
            String type,
            UUID category,
            String note,
            long amount,
            LocalDate date,
            LocalTime time,
            long balanceAfter) {
        jdbc.update(
                "INSERT INTO record.finance_transaction(id,owner_id,account_id,transaction_date,"
                        + "transaction_time,transaction_type,category_id,note,amount_minor,currency,balance_after_minor) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,'CNY',?)",
                id,
                owner,
                account,
                date,
                time,
                type,
                category,
                note,
                amount,
                balanceAfter);
    }

    /** 流水按本人、日期范围和条数限制读取。 */
    public List<FinanceDtos.Transaction> between(
            UUID owner, LocalDate start, LocalDate end, int limit) {
        return between(owner, start, end, limit, 0);
    }

    /** 使用稳定排序分页读取本人明细。 */
    public List<FinanceDtos.Transaction> between(
            UUID owner, LocalDate start, LocalDate end, int limit, int offset) {
        return jdbc.query(
                "SELECT t.id,t.account_id,t.transaction_type,c.name,t.note,t.amount_minor,"
                        + "t.transaction_date,t.transaction_time,a.balance_minor,t.balance_after_minor "
                        + "FROM record.finance_transaction t JOIN record.finance_account a ON a.id=t.account_id "
                        + "LEFT JOIN record.finance_category c ON c.id=t.category_id "
                        + "WHERE t.owner_id=? AND t.transaction_date>=? AND t.transaction_date<? "
                        + "ORDER BY t.transaction_date,t.transaction_time,t.id LIMIT ? OFFSET ?",
                (rs, index) ->
                        new FinanceDtos.Transaction(
                                rs.getObject(1, UUID.class),
                                rs.getObject(2, UUID.class),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getLong(6),
                                rs.getObject(7, LocalDate.class),
                                rs.getObject(8, LocalTime.class),
                                rs.getLong(9),
                                rs.getObject(10, Long.class)),
                owner,
                start,
                end,
                limit,
                offset);
    }

    /** 统计查询由财务服务公开，其他领域不直接访问财务表。 */
    public long totalAssets(UUID owner) {
        Long value =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(balance_minor),0) FROM record.finance_account "
                                + "WHERE owner_id=?",
                        Long.class,
                        owner);
        return value == null ? 0 : value;
    }

    public long totalExpense(UUID owner) {
        Long value =
                jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount_minor),0) FROM record.finance_transaction "
                                + "WHERE owner_id=? AND transaction_type='expense'",
                        Long.class,
                        owner);
        return value == null ? 0 : value;
    }

    /** 完整月计数按收支类型分别聚合，标题不含财务备注。 */
    public List<DailySummary> monthSummary(UUID owner, LocalDate start, LocalDate end) {
        return jdbc.query(
                """
                SELECT t.transaction_date AS day,t.transaction_type AS kind,count(*) AS count,
                       (array_agg(COALESCE(NULLIF(c.name,''),t.transaction_type)
                        ORDER BY t.transaction_time,t.id))[1:3] AS titles
                FROM record.finance_transaction t LEFT JOIN record.finance_category c ON c.id=t.category_id
                WHERE t.owner_id=? AND t.transaction_date>=? AND t.transaction_date<?
                GROUP BY t.transaction_date,t.transaction_type ORDER BY t.transaction_date,t.transaction_type
                """,
                (rs, n) -> DailySummaryMapper.map(rs, rs.getString("kind")),
                owner,
                start,
                end);
    }

    /** 趋势使用数据库完整聚合，不从有上限的明细列表累加。 */
    public long expenseOn(UUID owner, LocalDate date) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor),0) FROM record.finance_transaction "
                        + "WHERE owner_id=? AND transaction_date=? AND transaction_type='expense'",
                Long.class,
                owner,
                date);
    }

    /** 序列化同一用户与幂等键的写入；与余额更新使用同一个数据库事务。 */
    public void lockRequest(UUID owner, UUID requestId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("幂等锁必须在事务内取得");
        jdbc.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                (PreparedStatementCallback<Void>)
                        statement -> {
                            statement.setString(
                                    1, "recordagent:finance:" + owner + ":" + requestId);
                            statement.execute();
                            return null;
                        });
    }

    /** 幂等响应快照属于原始用户，摘要用来检测同一键承载不同请求。 */
    public record WriteRequest(String hash, String response) {}

    /** 读取成功提交的原始响应；没有缓存表示上次未成功提交。 */
    public Optional<WriteRequest> request(UUID owner, UUID requestId) {
        return jdbc
                .query(
                        "SELECT request_hash,response_body::text FROM record.finance_write_request WHERE owner_id=? AND request_id=?",
                        (rs, n) -> new WriteRequest(rs.getString(1), rs.getString(2)),
                        owner,
                        requestId)
                .stream()
                .findFirst();
    }

    /** 缓存和余额、流水一起提交；失败回滚后允许同一键安全重试。 */
    public void rememberRequest(
            UUID owner, UUID requestId, String hash, UUID transactionId, String response) {
        jdbc.update(
                "INSERT INTO record.finance_write_request(owner_id,request_id,request_hash,transaction_id,response_body) VALUES(?,?,?,?,?::jsonb)",
                owner,
                requestId,
                hash,
                transactionId,
                response);
    }

    /** 完整月统计，余额为当前账户总余额；仅普通收入/支出进入月收支。 */
    public FinanceDtos.MonthOverview overview(UUID owner, LocalDate start, LocalDate end) {
        return jdbc.queryForObject(
                """
  SELECT COALESCE((SELECT SUM(balance_minor) FROM record.finance_account WHERE owner_id=?),0),
  COALESCE(SUM(amount_minor) FILTER(WHERE transaction_type='expense'),0),
  COALESCE(SUM(amount_minor) FILTER(WHERE transaction_type='income'),0),COUNT(*)
  FROM record.finance_transaction WHERE owner_id=? AND transaction_date>=? AND transaction_date<?
  """,
                (r, n) ->
                        new FinanceDtos.MonthOverview(
                                r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4)),
                owner,
                owner,
                start,
                end);
    }

    /** 过滤在数据库执行，列表不会因分页而漏掉搜索匹配。 */
    public List<FinanceDtos.Transaction> filtered(
            UUID owner,
            LocalDate start,
            LocalDate end,
            String type,
            UUID account,
            String query,
            int offset,
            int limit) {
        // 无筛选时不计算每行备注的 lower/拼接；有筛选时用明确谓词保留索引选择空间。
        StringBuilder sql =
                new StringBuilder(
                        """
  SELECT t.id,t.account_id,t.transaction_type,c.name,t.note,t.amount_minor,t.transaction_date,t.transaction_time,a.balance_minor,t.balance_after_minor
  FROM record.finance_transaction t JOIN record.finance_account a ON a.id=t.account_id AND a.owner_id=t.owner_id
  LEFT JOIN record.finance_category c ON c.id=t.category_id AND c.owner_id=t.owner_id
  WHERE t.owner_id=? AND t.transaction_date>=? AND t.transaction_date<?
  """);
        List<Object> parameters = new ArrayList<>(List.of(owner, start, end));
        if (!type.isEmpty()) {
            sql.append(" AND t.transaction_type=?");
            parameters.add(type);
        }
        if (account != null) {
            sql.append(" AND t.account_id=?");
            parameters.add(account);
        }
        if (!query.isEmpty()) {
            sql.append(
                    " AND POSITION(lower(?) IN lower(COALESCE(c.name,'') || ' ' || COALESCE(t.note,'') || ' ' || a.name))>0");
            parameters.add(query);
        }
        sql.append(
                " ORDER BY t.transaction_date DESC,t.transaction_time DESC,t.id DESC LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query(
                sql.toString(),
                (r, n) ->
                        new FinanceDtos.Transaction(
                                r.getObject(1, UUID.class),
                                r.getObject(2, UUID.class),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getLong(6),
                                r.getObject(7, LocalDate.class),
                                r.getObject(8, LocalTime.class),
                                r.getLong(9),
                                r.getObject(10, Long.class)),
                parameters.toArray());
    }
}
