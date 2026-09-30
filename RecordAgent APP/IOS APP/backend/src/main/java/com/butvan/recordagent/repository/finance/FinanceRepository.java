package com.butvan.recordagent.repository.finance;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 收支表的用户隔离、账户锁和有界读取。 */
@Repository
@RequiredArgsConstructor
public class FinanceRepository {
    private final JdbcTemplate jdbc;

    public record LockedAccount(UUID id, long balance, long version) {}

    /** 期初余额是账户初始状态，不伪造一笔普通收入。 */
    public void createAccount(UUID id, UUID owner, String name, long balance) {
        jdbc.update("INSERT INTO record.finance_account(id,owner_id,name,account_type,currency,balance_minor) "
                + "VALUES(?,?,?,'cash','CNY',?)", id, owner, name, balance);
    }

    /** 账户列表仅返回当前用户。 */
    public List<FinanceDtos.Account> accounts(UUID owner, int limit) {
        return accounts(owner,limit,0);
    }

    /** 账户列表分页，按创建时间和稳定 ID 排序。 */
    public List<FinanceDtos.Account> accounts(UUID owner, int limit, int offset) {
        return jdbc.query("SELECT id,name,balance_minor,version FROM record.finance_account "
                + "WHERE owner_id=? ORDER BY created_at,id LIMIT ? OFFSET ?", (rs, index) ->
                new FinanceDtos.Account(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getLong(3), rs.getLong(4)), owner, limit, offset);
    }

    /** 写入前锁定本人账户，避免并发支出绕过余额约束。 */
    public Optional<LockedAccount> lockAccount(UUID owner, UUID id) {
        return jdbc.query("SELECT id,balance_minor,version FROM record.finance_account "
                + "WHERE owner_id=? AND id=? FOR UPDATE", (rs, index) ->
                new LockedAccount(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)),
                owner, id).stream().findFirst();
    }

    /** 在账户锁保护下原子更新余额和版本。 */
    public void updateBalance(UUID owner, UUID account, long balance) {
        jdbc.update("UPDATE record.finance_account SET balance_minor=?,version=version+1,updated_at=now() "
                + "WHERE owner_id=? AND id=?", balance, owner, account);
    }

    /** 分类名称属于同一用户与收支类型，不跨账户复用其他人的分类。 */
    public UUID category(UUID owner, String type, String name) {
        UUID candidate = UUID.randomUUID();
        jdbc.update("INSERT INTO record.finance_category(id,owner_id,transaction_type,name) "
                + "VALUES(?,?,?,?) ON CONFLICT(owner_id,transaction_type,name) DO NOTHING",
                candidate, owner, type, name);
        return jdbc.queryForObject("SELECT id FROM record.finance_category "
                + "WHERE owner_id=? AND transaction_type=? AND name=?", UUID.class, owner, type, name);
    }

    /** 与账户余额更新处于同一事务。 */
    public void createTransaction(UUID id, UUID owner, UUID account, String type, UUID category,
            String note, long amount, LocalDate date, LocalTime time) {
        jdbc.update("INSERT INTO record.finance_transaction(id,owner_id,account_id,transaction_date,"
                + "transaction_time,transaction_type,category_id,note,amount_minor,currency) "
                + "VALUES(?,?,?,?,?,?,?,?,?,'CNY')", id, owner, account, date, time,
                type, category, note, amount);
    }

    /** 流水按本人、日期范围和条数限制读取。 */
    public List<FinanceDtos.Transaction> between(UUID owner, LocalDate start, LocalDate end, int limit) {
        return between(owner, start, end, limit, 0);
    }

    /** 使用稳定排序分页读取本人明细。 */
    public List<FinanceDtos.Transaction> between(UUID owner, LocalDate start, LocalDate end, int limit, int offset) {
        return jdbc.query("SELECT t.id,t.account_id,t.transaction_type,c.name,t.note,t.amount_minor,"
                + "t.transaction_date,t.transaction_time,a.balance_minor "
                + "FROM record.finance_transaction t JOIN record.finance_account a ON a.id=t.account_id "
                + "LEFT JOIN record.finance_category c ON c.id=t.category_id "
                + "WHERE t.owner_id=? AND t.transaction_date>=? AND t.transaction_date<? "
                + "ORDER BY t.transaction_date,t.transaction_time,t.id LIMIT ? OFFSET ?", (rs, index) ->
                new FinanceDtos.Transaction(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6),
                        rs.getObject(7, LocalDate.class), rs.getObject(8, LocalTime.class), rs.getLong(9)),
                owner, start, end, limit, offset);
    }

    /** 统计查询由财务服务公开，其他领域不直接访问财务表。 */
    public long totalAssets(UUID owner) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(balance_minor),0) FROM record.finance_account "
                + "WHERE owner_id=?", Long.class, owner);
        return value == null ? 0 : value;
    }

    public long totalExpense(UUID owner) {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor),0) FROM record.finance_transaction "
                + "WHERE owner_id=? AND transaction_type='expense'", Long.class, owner);
        return value == null ? 0 : value;
    }
    /** 完整月计数按收支类型分别聚合，标题不含财务备注。 */
    public List<com.butvan.recordagent.service.query.DailySummary> monthSummary(UUID owner, LocalDate start, LocalDate end) {
        return jdbc.query("""
                SELECT t.transaction_date AS day,t.transaction_type AS kind,count(*) AS count,
                       (array_agg(COALESCE(NULLIF(c.name,''),t.transaction_type)
                        ORDER BY t.transaction_time,t.id))[1:3] AS titles
                FROM record.finance_transaction t LEFT JOIN record.finance_category c ON c.id=t.category_id
                WHERE t.owner_id=? AND t.transaction_date>=? AND t.transaction_date<?
                GROUP BY t.transaction_date,t.transaction_type ORDER BY t.transaction_date,t.transaction_type
                """, (rs, n) -> com.butvan.recordagent.repository.mapper.projection.DailySummaryMapper.map(rs, rs.getString("kind")), owner, start, end);
    }

    /** 趋势使用数据库完整聚合，不从有上限的明细列表累加。 */
    public long expenseOn(UUID owner, LocalDate date) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minor),0) FROM record.finance_transaction "
                + "WHERE owner_id=? AND transaction_date=? AND transaction_type='expense'", Long.class, owner, date);
    }

}
