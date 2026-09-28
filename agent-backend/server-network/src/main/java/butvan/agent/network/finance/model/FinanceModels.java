package butvan.agent.network.finance.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** 资产账户与收支流水领域模型。 */
public final class FinanceModels {

    private FinanceModels() {
    }

    /** 日期范围内的真实收支，排除划账及余额校准。 */
    public record CashflowSummary(BigDecimal income, BigDecimal expense) {}

    /** 用户持有的一个资金账户。 */
    public record FinanceAccount(
            String id, String name, String accountType, String currency, BigDecimal balance,
            boolean interestEnabled, BigDecimal annualRatePercent, LocalDate lastAccrualDate,
            int version, Instant createdAt, Instant updatedAt) {
    }

    /** 一条收入、支出、划账、余额校准或系统收益流水。 */
    public record FinanceTransaction(
            String id, String accountId, String accountName, LocalDate date, LocalTime time,
            String transactionType, String category, String note, BigDecimal amount,
            String currency, String source, Instant createdAt) {
    }

    /** 财务页一次加载所需的完整聚合。 */
    public record FinanceOverview(
            BigDecimal totalAssets, BigDecimal monthIncome, BigDecimal monthExpense,
            BigDecimal monthYield, List<FinanceAccount> accounts, List<FinanceTransaction> transactions) {
    }

    /** 图表中某个分类的当日金额。 */
    public record ExpenseCategoryAmount(String category, BigDecimal amount) {
    }

    /** 图表中某日的支出总额、收入总额与支出堆叠分类。 */
    public record ExpenseChartDay(
            LocalDate date, BigDecimal total, BigDecimal income, List<ExpenseCategoryAmount> categories) {
    }

    /** 财务收支图表所需的连续日期序列与同次读取的支出分类配色。 */
    public record ExpenseChart(
            String range, LocalDate from, LocalDate to, BigDecimal totalExpense, BigDecimal totalIncome,
            List<ExpenseChartDay> days, Map<String, String> categoryColors) {
    }

    /** 单笔支出排行保留来源，便于区分旧日历花销。 */
    public record RankedExpense(String id, String note, String category, LocalDate date,
                                BigDecimal amount, String source) {}

    /** 资产账户在指定期间的支出合计。 */
    public record AccountExpense(String accountId, String accountName, BigDecimal amount) {}

    /** 周、月、年账户支出及两种排行共用同一期间和支出事实。 */
    public record ExpenseInsights(String range, LocalDate from, LocalDate to,
                                  List<AccountExpense> accounts,
                                  List<RankedExpense> transactions,
                                  List<ExpenseCategoryAmount> categories, BigDecimal totalExpense) {}
}
