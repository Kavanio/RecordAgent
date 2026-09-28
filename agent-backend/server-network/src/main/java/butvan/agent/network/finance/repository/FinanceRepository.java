package butvan.agent.network.finance.repository;

import butvan.agent.network.finance.model.FinanceModels.FinanceAccount;
import butvan.agent.network.finance.model.FinanceModels.AccountExpense;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;

/** 财务域 SQLite 持久化适配器，金额统一以分保存。 */
@Repository
@RequiredArgsConstructor
public class FinanceRepository {

    /** 当月流水聚合结果。 */
    public record MonthTotals(BigDecimal income, BigDecimal expense, BigDecimal yield) {
    }

    /** 某日收入（包含手工收入与自动收益）的聚合结果。 */
    public record DailyIncomeTotal(LocalDate date, BigDecimal amount) {
    }

    private final JdbcTemplate jdbcTemplate;

    /** 账户收支分别统计 expense 与 income / yield；划账、余额校准和旧日历花销不参与。 */
    public List<AccountExpense> findAccountExpenses(String ownerId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT a.id AS account_id, a.name AS account_name, a.account_type AS account_type,
                       COALESCE(SUM(CASE WHEN t.transaction_type = 'expense' THEN t.amount_minor ELSE 0 END), 0) AS amount_minor,
                       COALESCE(SUM(CASE WHEN t.transaction_type IN ('income', 'yield') THEN t.amount_minor ELSE 0 END), 0) AS income_minor
                FROM finance_account a
                LEFT JOIN finance_transaction t ON t.account_id = a.id AND t.owner_id = a.owner_id
                    AND t.transaction_type IN ('expense', 'income', 'yield') AND t.transaction_date BETWEEN ? AND ?
                WHERE a.owner_id = ?
                GROUP BY a.id, a.name, a.account_type, a.created_at
                ORDER BY amount_minor DESC, a.created_at, a.id
                """, (rs, rowNum) -> new AccountExpense(rs.getString("account_id"),
                rs.getString("account_name"), rs.getString("account_type"), BigDecimal.valueOf(rs.getLong("amount_minor"), 2),
                BigDecimal.valueOf(rs.getLong("income_minor"), 2)),
                from.toString(), to.toString(), ownerId);
    }

    /** 查询用户在指定收支类型中使用过的分类。 */
    public List<String> findTransactionCategories(String ownerId, String transactionType) {
        return jdbcTemplate.queryForList("""
                SELECT name FROM finance_transaction_category
                WHERE owner_id = ? AND transaction_type = ?
                ORDER BY created_at, name
                """, String.class, ownerId, transactionType);
    }

    /** 查询当前用户指定收支类型已保存的分类颜色，不把历史空颜色作为覆盖值。 */
    public Map<String, String> findTransactionCategoryColors(String ownerId, String transactionType) {
        return jdbcTemplate.query("""
                SELECT name, color FROM finance_transaction_category
                WHERE owner_id = ? AND transaction_type = ? AND color IS NOT NULL
                ORDER BY created_at, name
                """, rs -> {
            Map<String, String> colors = new LinkedHashMap<>();
            while (rs.next()) colors.put(rs.getString("name"), rs.getString("color"));
            return colors;
        }, ownerId, transactionType);
    }

    /** 记住分类及可选颜色；重复使用保留原顺序，未提供颜色时保留已有配色。 */
    public void rememberTransactionCategory(
            String ownerId, String transactionType, String category, Instant now, String color) {
        jdbcTemplate.update("""
                INSERT INTO finance_transaction_category (
                    owner_id, transaction_type, name, created_at, color
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(owner_id, transaction_type, name)
                DO UPDATE SET color = COALESCE(excluded.color, finance_transaction_category.color)
                """, ownerId, transactionType, category, now.toString(), color);
    }

    /** 查询用户全部账户。 */
    public List<FinanceAccount> findAccounts(String ownerId) {
        return jdbcTemplate.query("""
                SELECT id, name, account_type, currency, balance_minor, interest_enabled,
                       annual_rate_percent, last_accrual_date, version, created_at, updated_at
                FROM finance_account WHERE owner_id = ? ORDER BY created_at, id
                """, (rs, rowNum) -> new FinanceAccount(
                rs.getString("id"), rs.getString("name"), rs.getString("account_type"), rs.getString("currency"),
                BigDecimal.valueOf(rs.getLong("balance_minor"), 2), rs.getBoolean("interest_enabled"),
                new BigDecimal(rs.getString("annual_rate_percent")), LocalDate.parse(rs.getString("last_accrual_date")),
                rs.getInt("version"), Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at"))), ownerId);
    }

    /** 按所有者查找账户。 */
    public Optional<FinanceAccount> findAccount(String ownerId, String accountId) {
        return findAccounts(ownerId).stream().filter(account -> account.id().equals(accountId)).findFirst();
    }

    /** 新建账户。 */
    public void insertAccount(String id, String ownerId, String name, String type, String currency, long balanceMinor,
                              boolean interestEnabled, BigDecimal annualRatePercent, LocalDate today, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO finance_account (
                    id, owner_id, name, account_type, currency, balance_minor, interest_enabled,
                    annual_rate_percent, last_accrual_date, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """, id, ownerId, name, type, currency, balanceMinor, interestEnabled,
                annualRatePercent.toPlainString(), today.toString(), now.toString(), now.toString());
    }

    /** 原子更新账户余额和结息日期。 */
    public void updateBalanceAndAccrualDate(
            String ownerId, String accountId, long balanceMinor, LocalDate accrualDate, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE finance_account
                SET balance_minor = ?, last_accrual_date = ?, version = version + 1, updated_at = ?
                WHERE owner_id = ? AND id = ?
                """, balanceMinor, accrualDate.toString(), now.toString(), ownerId, accountId);
        if (updated != 1) throw new IllegalArgumentException("资产账户不存在");
    }

    /** 原子调整账户余额，余额不足时拒绝支出。 */
    public boolean adjustBalance(String ownerId, String accountId, long deltaMinor, Instant now) {
        return jdbcTemplate.update("""
                UPDATE finance_account SET balance_minor = balance_minor + ?, version = version + 1, updated_at = ?
                WHERE owner_id = ? AND id = ? AND balance_minor + ? >= 0
                """, deltaMinor, now.toString(), ownerId, accountId, deltaMinor) == 1;
    }

    /** 保存流水。 */
    public void insertTransaction(
            String id, String ownerId, String accountId, LocalDate date, LocalTime time, String type,
            String category, String note, long amountMinor, String currency, String source, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO finance_transaction (
                    id, owner_id, account_id, transaction_date, transaction_time, transaction_type,
                    category, note, amount_minor, currency, source, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, ownerId, accountId, date.toString(), time.toString(), type, category, note,
                amountMinor, currency, source, now.toString());
    }

    /** 按所有者读取单条财务流水。 */
    public Optional<FinanceTransaction> findTransaction(String ownerId, String transactionId) {
        return jdbcTemplate.query("""
                SELECT t.id, t.account_id, a.name AS account_name, t.transaction_date, t.transaction_time,
                       t.transaction_type, t.category, t.note, t.amount_minor, t.currency, t.source, t.created_at
                FROM finance_transaction t
                JOIN finance_account a ON a.id = t.account_id
                WHERE t.owner_id = ? AND t.id = ?
                """, (rs, rowNum) -> new FinanceTransaction(
                rs.getString("id"), rs.getString("account_id"), rs.getString("account_name"),
                LocalDate.parse(rs.getString("transaction_date")), LocalTime.parse(rs.getString("transaction_time")),
                rs.getString("transaction_type"), rs.getString("category"), rs.getString("note"),
                BigDecimal.valueOf(rs.getLong("amount_minor"), 2), rs.getString("currency"),
                rs.getString("source"), Instant.parse(rs.getString("created_at"))), ownerId, transactionId)
                .stream().findFirst();
    }

    /** 更新一条归属当前用户的手工流水内容。 */
    public void updateTransaction(
            String ownerId, String transactionId, String accountId, LocalDate date, LocalTime time,
            String type, String category, String note, long amountMinor, String currency) {
        int updated = jdbcTemplate.update("""
                UPDATE finance_transaction
                SET account_id = ?, transaction_date = ?, transaction_time = ?, transaction_type = ?,
                    category = ?, note = ?, amount_minor = ?, currency = ?
                WHERE owner_id = ? AND id = ?
                """, accountId, date.toString(), time.toString(), type, category, note, amountMinor,
                currency, ownerId, transactionId);
        if (updated != 1) throw new IllegalArgumentException("财务流水不存在");
    }

    /** 查询最近流水。 */
    public List<FinanceTransaction> findTransactions(String ownerId, int limit) {
        return jdbcTemplate.query("""
                SELECT t.id, t.account_id, a.name AS account_name, t.transaction_date, t.transaction_time,
                       t.transaction_type, t.category, t.note, t.amount_minor, t.currency, t.source, t.created_at
                FROM finance_transaction t
                JOIN finance_account a ON a.id = t.account_id
                WHERE t.owner_id = ?
                ORDER BY t.transaction_date DESC, t.transaction_time DESC, t.created_at DESC
                LIMIT ?
                """, (rs, rowNum) -> new FinanceTransaction(
                rs.getString("id"), rs.getString("account_id"), rs.getString("account_name"),
                LocalDate.parse(rs.getString("transaction_date")), LocalTime.parse(rs.getString("transaction_time")),
                rs.getString("transaction_type"), rs.getString("category"), rs.getString("note"),
                BigDecimal.valueOf(rs.getLong("amount_minor"), 2), rs.getString("currency"),
                rs.getString("source"), Instant.parse(rs.getString("created_at"))), ownerId, limit);
    }

    /** 查询用户全部财务流水，供完整流水视图按需加载。 */
    public List<FinanceTransaction> findAllTransactions(String ownerId) {
        return jdbcTemplate.query("""
                SELECT t.id, t.account_id, a.name AS account_name, t.transaction_date, t.transaction_time,
                       t.transaction_type, t.category, t.note, t.amount_minor, t.currency, t.source, t.created_at
                FROM finance_transaction t
                JOIN finance_account a ON a.id = t.account_id
                WHERE t.owner_id = ?
                ORDER BY t.transaction_date DESC, t.transaction_time DESC, t.created_at DESC
                """, (rs, rowNum) -> new FinanceTransaction(
                rs.getString("id"), rs.getString("account_id"), rs.getString("account_name"),
                LocalDate.parse(rs.getString("transaction_date")), LocalTime.parse(rs.getString("transaction_time")),
                rs.getString("transaction_type"), rs.getString("category"), rs.getString("note"),
                BigDecimal.valueOf(rs.getLong("amount_minor"), 2), rs.getString("currency"),
                rs.getString("source"), Instant.parse(rs.getString("created_at"))), ownerId);
    }

    /** 查询指定日期实际发生变动的资产流水。 */
    public List<FinanceTransaction> findDayTransactions(String ownerId, LocalDate date) {
        return jdbcTemplate.query("""
                SELECT t.id, t.account_id, a.name AS account_name, t.transaction_date, t.transaction_time,
                       t.transaction_type, t.category, t.note, t.amount_minor, t.currency, t.source, t.created_at
                FROM finance_transaction t
                JOIN finance_account a ON a.id = t.account_id
                WHERE t.owner_id = ? AND t.transaction_date = ?
                ORDER BY t.transaction_date DESC, t.transaction_time DESC, t.created_at DESC
                """, (rs, rowNum) -> new FinanceTransaction(
                rs.getString("id"), rs.getString("account_id"), rs.getString("account_name"),
                LocalDate.parse(rs.getString("transaction_date")), LocalTime.parse(rs.getString("transaction_time")),
                rs.getString("transaction_type"), rs.getString("category"), rs.getString("note"),
                BigDecimal.valueOf(rs.getLong("amount_minor"), 2), rs.getString("currency"),
                rs.getString("source"), Instant.parse(rs.getString("created_at"))), ownerId, date.toString());
    }

    /** 聚合指定月份的收入、支出与自动收益。 */
    public MonthTotals summarizeMonth(String ownerId, LocalDate monthStart, LocalDate nextMonthStart) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COALESCE(SUM(CASE WHEN transaction_type IN ('income', 'yield') THEN amount_minor ELSE 0 END), 0) income_minor,
                    COALESCE(SUM(CASE WHEN transaction_type = 'expense' THEN amount_minor ELSE 0 END), 0) expense_minor,
                    COALESCE(SUM(CASE WHEN transaction_type = 'yield' THEN amount_minor ELSE 0 END), 0) yield_minor
                FROM finance_transaction
                WHERE owner_id = ? AND transaction_date >= ? AND transaction_date < ?
                """, (rs, rowNum) -> new MonthTotals(
                BigDecimal.valueOf(rs.getLong("income_minor"), 2),
                BigDecimal.valueOf(rs.getLong("expense_minor"), 2),
                BigDecimal.valueOf(rs.getLong("yield_minor"), 2)),
                ownerId, monthStart.toString(), nextMonthStart.toString());
    }

    /** 聚合指定闭开日期区间内的每日收入，自动收益按收入统计。 */
    public List<DailyIncomeTotal> findDailyIncomeTotals(String ownerId, LocalDate from, LocalDate toExclusive) {
        return jdbcTemplate.query("""
                SELECT transaction_date,
                       COALESCE(SUM(amount_minor), 0) AS income_minor
                FROM finance_transaction
                WHERE owner_id = ?
                  AND transaction_type IN ('income', 'yield')
                  AND transaction_date >= ? AND transaction_date < ?
                GROUP BY transaction_date
                ORDER BY transaction_date
                """, (rs, rowNum) -> new DailyIncomeTotal(
                LocalDate.parse(rs.getString("transaction_date")),
                BigDecimal.valueOf(rs.getLong("income_minor"), 2)),
                ownerId, from.toString(), toExclusive.toString());
    }
}
