package butvan.agent.network.finance.dto;

import butvan.agent.network.finance.model.FinanceModels.FinanceAccount;
import butvan.agent.network.finance.model.FinanceModels.FinanceOverview;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import butvan.agent.network.finance.model.FinanceModels.ExpenseChart;
import butvan.agent.network.finance.model.FinanceModels.ExpenseInsights;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** 财务接口请求、响应与领域映射。 */
public final class FinanceDtos {

    private FinanceDtos() {
    }

    /** 月历使用的轻量收支汇总响应。 */
    public record CashflowSummaryResponse(BigDecimal income, BigDecimal expense) {}

    public record CreateAccountRequest(
            String name, String accountType, String currency, BigDecimal initialBalance,
            boolean interestEnabled, BigDecimal annualRatePercent) {
    }

    public record CreateBalanceAdjustmentRequest(String direction, BigDecimal amount, String note) {
    }

    /** categoryColor 可选，保存后应用于当前用户同一收支类型的同名分类。 */
    public record CreateTransactionRequest(
            String accountId, String transactionType, String category, String note,
            BigDecimal amount, LocalDate date, LocalTime time, String categoryColor) {
    }

    /** 未提供 categoryColor 时保持原有分类颜色。 */
    public record UpdateTransactionRequest(
            String accountId, String transactionType, String category, String note,
            BigDecimal amount, LocalDate date, LocalTime time, String categoryColor) {
    }

    public record CreateTransferRequest(
            String fromAccountId, String toAccountId, BigDecimal amount, String note,
            LocalDate date, LocalTime time) {
    }

    public record AccountResponse(
            String id, String name, String accountType, String currency, BigDecimal balance,
            boolean interestEnabled, BigDecimal annualRatePercent, LocalDate lastAccrualDate, int version) {
    }

    public record TransactionResponse(
            String id, String accountId, String accountName, LocalDate date, LocalTime time,
            String transactionType, String category, String note, BigDecimal amount,
            String currency, String source, Instant createdAt) {
    }

    public record TransferResponse(
            TransactionResponse fromTransaction, TransactionResponse toTransaction) {
    }

    public record OverviewResponse(
            BigDecimal totalAssets, BigDecimal monthIncome, BigDecimal monthExpense, BigDecimal monthYield,
            List<AccountResponse> accounts, List<TransactionResponse> transactions) {
    }

    public record ExpenseCategoryResponse(String category, BigDecimal amount) {
    }

    public record ExpenseChartDayResponse(
            LocalDate date, BigDecimal total, BigDecimal income, List<ExpenseCategoryResponse> categories) {
    }

    public record ExpenseChartResponse(
            String range, LocalDate from, LocalDate to, BigDecimal totalExpense, BigDecimal totalIncome,
            List<ExpenseChartDayResponse> days, Map<String, String> categoryColors) {
    }

    /** 账户图、单笔排行和分类排行共用同一次期间查询。 */
    public record AccountExpenseResponse(String accountId, String accountName, BigDecimal amount, BigDecimal income) {}
    public record RankedExpenseResponse(String id, String note, String category, LocalDate date,
                                        BigDecimal amount, String source) {}
    public record ExpenseInsightsResponse(String range, LocalDate from, LocalDate to,
                                          BigDecimal totalExpense, List<AccountExpenseResponse> accounts,
                                          List<RankedExpenseResponse> transactions,
                                          List<ExpenseCategoryResponse> categories) {}

    /** 分类名称保持兼容，colors 按 expense / income 返回已保存的颜色覆盖。 */
    public record TransactionCategoryOptionsResponse(
            List<String> expense, List<String> income, Map<String, Map<String, String>> colors) {
    }

    public static TransactionCategoryOptionsResponse categoryOptions(
            Map<String, List<String>> categories, Map<String, Map<String, String>> colors) {
        return new TransactionCategoryOptionsResponse(categories.get("expense"), categories.get("income"), colors);
    }

    public static AccountResponse from(FinanceAccount account) {
        return new AccountResponse(account.id(), account.name(), account.accountType(), account.currency(),
                account.balance(), account.interestEnabled(), account.annualRatePercent(),
                account.lastAccrualDate(), account.version());
    }

    public static TransactionResponse from(FinanceTransaction transaction) {
        return new TransactionResponse(transaction.id(), transaction.accountId(), transaction.accountName(),
                transaction.date(), transaction.time(), transaction.transactionType(), transaction.category(),
                transaction.note(), transaction.amount(), transaction.currency(), transaction.source(),
                transaction.createdAt());
    }

    public static TransferResponse from(FinanceTransaction from, FinanceTransaction to) {
        return new TransferResponse(from(from), from(to));
    }

    public static OverviewResponse from(FinanceOverview overview) {
        return new OverviewResponse(overview.totalAssets(), overview.monthIncome(), overview.monthExpense(),
                overview.monthYield(), overview.accounts().stream().map(FinanceDtos::from).toList(),
                overview.transactions().stream().map(FinanceDtos::from).toList());
    }

    public static ExpenseChartResponse from(ExpenseChart chart) {
        return new ExpenseChartResponse(chart.range(), chart.from(), chart.to(), chart.totalExpense(), chart.totalIncome(),
                chart.days().stream().map(day -> new ExpenseChartDayResponse(
                        day.date(), day.total(), day.income(), day.categories().stream()
                        .map(item -> new ExpenseCategoryResponse(item.category(), item.amount())).toList())).toList(), chart.categoryColors());
    }

    public static ExpenseInsightsResponse from(ExpenseInsights insights) {
        return new ExpenseInsightsResponse(insights.range(), insights.from(), insights.to(), insights.totalExpense(),
                insights.accounts().stream().map(account -> new AccountExpenseResponse(
                        account.accountId(), account.accountName(), account.amount(), account.income())).toList(),
                insights.transactions().stream().map(entry -> new RankedExpenseResponse(
                        entry.id(), entry.note(), entry.category(), entry.date(), entry.amount(), entry.source())).toList(),
                insights.categories().stream().map(item -> new ExpenseCategoryResponse(
                        item.category(), item.amount())).toList());
    }
}
