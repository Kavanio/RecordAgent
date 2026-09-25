package butvan.agent.network.daily.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨日记录与财务流水的只读收支投影，保证日历、财务总览和趋势图使用同一统计口径。
 */
@Service
@RequiredArgsConstructor
public class ExpenseAnalyticsService {

    /** 一笔可参与统一统计的支出。 */
    public record ExpenseEntry(
            String id, LocalDate date, LocalTime time, String category, String note,
            BigDecimal amount, String currency, String source, Instant createdAt) {
    }

    /** 一笔可在日历中只读展示的财务收入。 */
    public record IncomeEntry(
            String id, LocalDate date, LocalTime time, String category, String note,
            BigDecimal amount, String currency, String source, Instant createdAt) {
    }

    /** 某日的支出总额、分类构成以及来自财务模块的记录数量。 */
    public record ExpenseDay(
            LocalDate date, BigDecimal total, Map<String, BigDecimal> categories,
            int financeExpenseCount, String financeHeadline) {
    }

    /** 连续日期范围内的支出分析。 */
    public record ExpenseAnalysis(
            LocalDate from, LocalDate to, BigDecimal totalExpense, List<ExpenseDay> days) {
    }

    private final JdbcTemplate jdbcTemplate;

    /** 返回包含零支出日期的连续序列，便于折线与柱状图共享横轴。 */
    @Transactional(readOnly = true)
    public ExpenseAnalysis analyze(String ownerId, LocalDate from, LocalDate to) {
        validate(ownerId, from, to);
        List<ExpenseEntry> entries = findExpenses(ownerId, from, to);
        Map<LocalDate, List<ExpenseEntry>> byDate = new LinkedHashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            byDate.put(date, new ArrayList<>());
        }
        entries.forEach(entry -> byDate.get(entry.date()).add(entry));

        List<ExpenseDay> days = byDate.entrySet().stream().map(item -> {
            Map<String, BigDecimal> categories = new LinkedHashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            int financeCount = 0;
            String financeHeadline = null;
            for (ExpenseEntry entry : item.getValue()) {
                total = total.add(entry.amount());
                categories.merge(entry.category(), entry.amount(), BigDecimal::add);
                if ("finance".equals(entry.source())) {
                    financeCount++;
                    if (financeHeadline == null) financeHeadline = entry.note();
                }
            }
            return new ExpenseDay(item.getKey(), total, categories, financeCount, financeHeadline);
        }).toList();
        BigDecimal total = days.stream().map(ExpenseDay::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExpenseAnalysis(from, to, total, days);
    }

    /** 查询某日从财务页记入的支出，用于日历详情只读展示。 */
    @Transactional(readOnly = true)
    public List<ExpenseEntry> findFinanceExpenses(String ownerId, LocalDate date) {
        validate(ownerId, date, date);
        return findExpenses(ownerId, date, date).stream()
                .filter(entry -> "finance".equals(entry.source()))
                .toList();
    }

    /** 查询某日从财务模块记入的收入与自动收益，供日历详情只读展示。 */
    @Transactional(readOnly = true)
    public List<IncomeEntry> findFinanceIncomes(String ownerId, LocalDate date) {
        validate(ownerId, date, date);
        return jdbcTemplate.query("""
                SELECT id, transaction_date, transaction_time, category, note,
                       amount_minor, currency, source, created_at
                FROM finance_transaction
                WHERE owner_id = ? AND transaction_type IN ('income', 'yield') AND transaction_date = ?
                ORDER BY transaction_time, created_at, id
                """, (resultSet, rowNumber) -> new IncomeEntry(
                resultSet.getString("id"), LocalDate.parse(resultSet.getString("transaction_date")),
                LocalTime.parse(resultSet.getString("transaction_time")), resultSet.getString("category"),
                resultSet.getString("note"), BigDecimal.valueOf(resultSet.getLong("amount_minor"), 2),
                resultSet.getString("currency"), resultSet.getString("source"),
                Instant.parse(resultSet.getString("created_at"))), ownerId, date.toString());
    }

    /** 查询最近从旧日历入口记录的支出，供财务流水兼容展示。 */
    @Transactional(readOnly = true)
    public List<ExpenseEntry> findRecentCalendarExpenses(String ownerId, int limit) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
        if (limit <= 0) throw new IllegalArgumentException("查询数量必须大于零");
        return jdbcTemplate.query("""
                SELECT e.id, e.event_date AS expense_date, x.expense_time, x.category, x.note,
                       x.amount_minor, x.currency, e.created_at
                FROM daily_event e
                JOIN expense_detail x ON x.event_id = e.id
                WHERE e.owner_id = ?
                ORDER BY e.event_date DESC, x.expense_time DESC, e.created_at DESC, e.id DESC
                LIMIT ?
                """, (resultSet, rowNumber) -> new ExpenseEntry(
                resultSet.getString("id"), LocalDate.parse(resultSet.getString("expense_date")),
                LocalTime.parse(resultSet.getString("expense_time")), resultSet.getString("category"),
                resultSet.getString("note"), BigDecimal.valueOf(resultSet.getLong("amount_minor"), 2),
                resultSet.getString("currency"), "calendar", Instant.parse(resultSet.getString("created_at"))),
                ownerId, limit);
    }

    /** 查询全部旧日历花销，供财务完整流水视图兼容展示。 */
    @Transactional(readOnly = true)
    public List<ExpenseEntry> findAllCalendarExpenses(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
        return jdbcTemplate.query("""
                SELECT e.id, e.event_date AS expense_date, x.expense_time, x.category, x.note,
                       x.amount_minor, x.currency, e.created_at
                FROM daily_event e
                JOIN expense_detail x ON x.event_id = e.id
                WHERE e.owner_id = ?
                ORDER BY e.event_date DESC, x.expense_time DESC, e.created_at DESC, e.id DESC
                """, (resultSet, rowNumber) -> new ExpenseEntry(
                resultSet.getString("id"), LocalDate.parse(resultSet.getString("expense_date")),
                LocalTime.parse(resultSet.getString("expense_time")), resultSet.getString("category"),
                resultSet.getString("note"), BigDecimal.valueOf(resultSet.getLong("amount_minor"), 2),
                resultSet.getString("currency"), "calendar", Instant.parse(resultSet.getString("created_at"))),
                ownerId);
    }

    /** 为日报提供已去重的支出事实，调用方按币种与截止时间分组。 */
    @Transactional(readOnly = true)
    public List<ExpenseEntry> reportExpenses(String ownerId, LocalDate date) {
        validate(ownerId, date, date);
        return findExpenses(ownerId, date, date);
    }

    /** 为周期报告提供闭区间支出事实，保持币种独立。 */
    @Transactional(readOnly = true)
    public List<ExpenseEntry> reportExpenses(String ownerId, LocalDate from, LocalDate to) {
        validate(ownerId, from, to);
        return findExpenses(ownerId, from, to);
    }

    private List<ExpenseEntry> findExpenses(String ownerId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT id, expense_date, expense_time, category, note, amount_minor, currency, source, created_at
                FROM (
                    SELECT e.id AS id, e.event_date AS expense_date, x.expense_time AS expense_time,
                           x.category AS category, x.note AS note, x.amount_minor AS amount_minor,
                           x.currency AS currency, 'calendar' AS source, e.created_at AS created_at
                    FROM daily_event e
                    JOIN expense_detail x ON x.event_id = e.id
                    WHERE e.owner_id = ? AND e.event_date BETWEEN ? AND ?
                    UNION ALL
                    SELECT t.id AS id, t.transaction_date AS expense_date, t.transaction_time AS expense_time,
                           t.category AS category, t.note AS note, t.amount_minor AS amount_minor,
                           t.currency AS currency, 'finance' AS source, t.created_at AS created_at
                    FROM finance_transaction t
                    WHERE t.owner_id = ? AND t.transaction_type = 'expense'
                      AND t.transaction_date BETWEEN ? AND ?
                ) expenses
                ORDER BY expense_date, expense_time, created_at, id
                """, (resultSet, rowNumber) -> new ExpenseEntry(
                resultSet.getString("id"), LocalDate.parse(resultSet.getString("expense_date")),
                LocalTime.parse(resultSet.getString("expense_time")), resultSet.getString("category"),
                resultSet.getString("note"), BigDecimal.valueOf(resultSet.getLong("amount_minor"), 2),
                resultSet.getString("currency"), resultSet.getString("source"),
                Instant.parse(resultSet.getString("created_at"))),
                ownerId, from.toString(), to.toString(), ownerId, from.toString(), to.toString());
    }

    private void validate(String ownerId, LocalDate from, LocalDate to) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
        if (from == null || to == null || from.isAfter(to)) throw new IllegalArgumentException("日期范围不合法");
    }
}
