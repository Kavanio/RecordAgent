package butvan.agent.network.finance.service;

import butvan.agent.network.config.database.LocalDatabaseConfiguration;
import butvan.agent.network.daily.DailyEventModuleConfiguration;
import butvan.agent.network.daily.model.DailyEventModels.ExpenseCommand;
import butvan.agent.network.daily.model.DailyEventModels.IncomeDetails;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.finance.model.FinanceModels.FinanceAccount;
import butvan.agent.network.finance.model.FinanceModels.FinanceOverview;
import butvan.agent.network.finance.model.FinanceModels.ExpenseChart;
import butvan.agent.network.finance.repository.FinanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 通过公开领域服务验证账户余额与收支聚合。 */
@SpringBootTest(classes = {
        LocalDatabaseConfiguration.class,
        DailyEventModuleConfiguration.class,
        FinanceRepository.class,
        FinanceService.class
})
class FinanceServiceIntegrationTest {

    private static final Path DATABASE_PATH = createDatabasePath();

    @jakarta.annotation.Resource
    private FinanceService financeService;

    @jakarta.annotation.Resource
    private DailyEventService dailyEventService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("butvan.database.path", DATABASE_PATH::toString);
    }

    @Test
    void reportBalancesReverseLaterLedgerChangesAndKeepOwnerAndCreationBoundaries() {
        String owner = "report-history-user";
        LocalDate today = LocalDate.now();
        var zone = java.time.ZoneId.systemDefault();
        var a = financeService.createAccount(owner, "账户A", "bank", "CNY", new BigDecimal("1000"), false, BigDecimal.ZERO);
        var b = financeService.createAccount(owner, "账户B", "bank", "CNY", new BigDecimal("500"), false, BigDecimal.ZERO);
        financeService.createAccount("another-report-user", "其他用户", "bank", "CNY", new BigDecimal("9000"), false, BigDecimal.ZERO);
        financeService.adjustAccountBalance(owner, a.id(), "increase", new BigDecimal("50"), "校准不算收入");
        financeService.createTransaction(owner, a.id(), "income", "工资", "未来入账", new BigDecimal("200"), today.plusDays(1), LocalTime.NOON);
        financeService.createTransaction(owner, a.id(), "expense", "餐饮", "未来支出", new BigDecimal("30"), today.plusDays(2), LocalTime.NOON);
        financeService.transfer(owner, a.id(), b.id(), new BigDecimal("100"), "未来划账", today.plusDays(1), LocalTime.NOON);
        var current = financeService.getReportAssets(owner, today, zone);
        assertEquals(2, current.size());
        assertEquals(new BigDecimal("1050.00"), current.stream().filter(item -> item.id().equals(a.id())).findFirst().orElseThrow().balance());
        assertEquals(new BigDecimal("500.00"), current.stream().filter(item -> item.id().equals(b.id())).findFirst().orElseThrow().balance());
        assertEquals(0, financeService.getReportAssets(owner, today.minusDays(1), zone).size());
        var range = financeService.getReportTransactions(owner, today.plusDays(1), today.plusDays(1));
        assertEquals(3, range.size());
        assertEquals(0, financeService.getReportTransactions("unknown-report-user", today, today.plusDays(1)).size());
        assertThrows(IllegalArgumentException.class, () -> financeService.getReportTransactions(owner, today, today.minusDays(1)));
    }

    @Test
    void incomeAndExpenseUpdateSelectedAccountAndMonthlyTotals() {
        FinanceAccount account = financeService.createAccount(
                "finance-ledger-user", "微信零钱通", "wechat", "CNY", new BigDecimal("1000.00"),
                true, new BigDecimal("1.850000"));

        financeService.createTransaction("finance-ledger-user", account.id(), "income", "工资", "九月工资",
                new BigDecimal("500.00"), LocalDate.now(), LocalTime.of(9, 0));
        financeService.createTransaction("finance-ledger-user", account.id(), "expense", "餐饮", "午餐",
                new BigDecimal("28.50"), LocalDate.now(), LocalTime.of(12, 0));

        FinanceOverview overview = financeService.getOverview("finance-ledger-user");
        assertEquals(new BigDecimal("1471.50"), overview.totalAssets());
        assertEquals(new BigDecimal("500.00"), overview.monthIncome());
        assertEquals(new BigDecimal("28.50"), overview.monthExpense());
        assertEquals(2, overview.transactions().size());
        assertEquals(2, financeService.getTransactions("finance-ledger-user").size());
    }

    @Test
    void transferBetweenAccountsUpdatesBalancesAndKeepsTotalAssetsAndMonthlyTotalsUnchanged() {
        String ownerId = "finance-transfer-user";
        FinanceAccount from = financeService.createAccount(
                ownerId, "招商银行", "bank", "CNY", new BigDecimal("1000.00"), false, BigDecimal.ZERO);
        FinanceAccount to = financeService.createAccount(
                ownerId, "微信零钱通", "wechat_yield", "CNY", new BigDecimal("500.00"), false, BigDecimal.ZERO);

        var transactions = financeService.transfer(
                ownerId, from.id(), to.id(), new BigDecimal("300.00"), "转入零钱通",
                LocalDate.now(), LocalTime.of(14, 0));

        assertEquals(2, transactions.size());
        assertEquals("transfer_out", transactions.get(0).transactionType());
        assertEquals("transfer_in", transactions.get(1).transactionType());

        FinanceOverview overview = financeService.getOverview(ownerId);
        assertEquals(new BigDecimal("1500.00"), overview.totalAssets());
        assertEquals(0, overview.monthIncome().compareTo(BigDecimal.ZERO));
        assertEquals(0, overview.monthExpense().compareTo(BigDecimal.ZERO));

        var accounts = overview.accounts();
        var updatedFrom = accounts.stream().filter(a -> a.id().equals(from.id())).findFirst().orElseThrow();
        var updatedTo = accounts.stream().filter(a -> a.id().equals(to.id())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("700.00"), updatedFrom.balance());
        assertEquals(new BigDecimal("800.00"), updatedTo.balance());

        // 余额不足校验
        var error = assertThrows(IllegalArgumentException.class, () ->
                financeService.transfer(ownerId, from.id(), to.id(), new BigDecimal("9999.00"),
                        "超额划账", LocalDate.now(), LocalTime.of(15, 0)));
        assertEquals("转出账户余额不足，无法划账", error.getMessage());

        // 相同账户划转校验
        var sameError = assertThrows(IllegalArgumentException.class, () ->
                financeService.transfer(ownerId, from.id(), from.id(), new BigDecimal("100.00"),
                        "同账户划转", LocalDate.now(), LocalTime.of(15, 0)));
        assertEquals("转出账户与转入账户不能相同", sameError.getMessage());
    }

    @Test
    void monthlyExpenseIncludesLegacyExpensesRecordedFromCalendar() {
        String ownerId = "finance-calendar-consistency-user";
        LocalDate today = LocalDate.now();
        dailyEventService.create(ownerId,
                new ExpenseCommand(today, "餐饮", "日历中的午餐", new BigDecimal("82.39"), "12:10", "CNY"));

        FinanceOverview overview = financeService.getOverview(ownerId);

        assertEquals(new BigDecimal("82.39"), overview.monthExpense());
    }

    @Test
    void recentTransactionsIncludeLegacyExpensesRecordedFromCalendar() {
        String ownerId = "finance-calendar-recent-user";
        LocalDate today = LocalDate.now();
        dailyEventService.create(ownerId,
                new ExpenseCommand(today, "餐饮", "日历中的晚餐", new BigDecimal("36.80"), "18:30", "CNY"));

        FinanceOverview overview = financeService.getOverview(ownerId);

        assertEquals(1, overview.transactions().size());
        assertEquals("日历中的晚餐", overview.transactions().getFirst().note());
    }

    @Test
    void financeExpensesAppearInCalendarAndChartWithCategoryStacks() {
        String ownerId = "finance-unified-analysis-user";
        LocalDate today = LocalDate.now();
        FinanceAccount account = financeService.createAccount(
                ownerId, "现金", "cash", "CNY", new BigDecimal("500.00"), false, BigDecimal.ZERO);
        financeService.createTransaction(ownerId, account.id(), "expense", "餐饮", "午餐",
                new BigDecimal("30.00"), today, LocalTime.NOON);
        financeService.createTransaction(ownerId, account.id(), "expense", "日常", "纸巾",
                new BigDecimal("12.50"), today, LocalTime.of(13, 0));
        financeService.createTransaction(ownerId, account.id(), "income", "报销", "午餐报销",
                new BigDecimal("80.00"), today, LocalTime.of(14, 0));

        var calendarSummary = dailyEventService.getDays(ownerId, today, today).getFirst();
        ExpenseChart chart = financeService.getExpenseChart(ownerId, "month");
        var chartDay = chart.days().stream().filter(day -> day.date().equals(today)).findFirst().orElseThrow();
        var incomeEvent = dailyEventService.getDay(ownerId, today).events().stream()
                .filter(event -> "income".equals(event.eventType())).findFirst().orElseThrow();

        assertEquals(new BigDecimal("42.50"), calendarSummary.expenseTotal());
        assertEquals(2, calendarSummary.eventCount());
        assertEquals(new BigDecimal("42.50"), chartDay.total());
        assertEquals(new BigDecimal("80.00"), chartDay.income());
        assertEquals(new BigDecimal("80.00"), chart.totalIncome());
        assertEquals(new BigDecimal("80.00"), assertInstanceOf(IncomeDetails.class, incomeEvent.details()).amount());
        assertEquals(2, chartDay.categories().size());
        var summary = financeService.getCashflowSummary(ownerId, today, today);
        assertEquals(new BigDecimal("80.00"), summary.income());
        assertEquals(new BigDecimal("42.50"), summary.expense());
        assertEquals(0, financeService.getCashflowSummary("another-owner", today, today).income().signum());
        assertEquals(0, financeService.getCashflowSummary(ownerId, today.minusDays(1), today.minusDays(1)).income().signum());
        assertThrows(IllegalArgumentException.class, () -> financeService.getCashflowSummary(ownerId, today, today.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> financeService.getCashflowSummary(ownerId, today.minusDays(367), today));
    }

    @Test
    void expenseInsightsSeparateAccountSpendFromLegacyExpensesAndExcludeTransfers() {
        String owner = "finance-insights-user";
        LocalDate today = LocalDate.now();
        var first = financeService.createAccount(owner, "日常", "bank", "CNY", new BigDecimal("500.00"), false, BigDecimal.ZERO);
        var second = financeService.createAccount(owner, "备用", "bank", "CNY", new BigDecimal("200.00"), false, BigDecimal.ZERO);
        financeService.createTransaction(owner, first.id(), "expense", "餐饮", "午餐",
                new BigDecimal("30.00"), today, LocalTime.NOON);
        financeService.createTransaction(owner, second.id(), "expense", "交通", "打车",
                new BigDecimal("80.00"), today, LocalTime.of(13, 0));
        financeService.transfer(owner, first.id(), second.id(), new BigDecimal("20.00"),
                "划账", today, LocalTime.of(14, 0));
        dailyEventService.create(owner, new ExpenseCommand(today, "餐饮", "旧日历花销",
                new BigDecimal("15.00"), "15:00", "CNY"));

        financeService.createTransaction(owner, first.id(), "income", "工资", "收入测试",
                new BigDecimal("50.00"), today, LocalTime.NOON);
        financeService.createTransaction(owner, first.id(), "income", "工资", "往年收入",
                new BigDecimal("70.00"), today.minusYears(1), LocalTime.NOON);
        financeService.adjustAccountBalance(owner, first.id(), "increase", new BigDecimal("60.00"), "余额校准");
        var insights = financeService.getExpenseInsights(owner, "month");
        for (String range : java.util.List.of("week", "month", "year")) {
            var accounts = financeService.getExpenseInsights(owner, range).accounts();
            assertEquals(new BigDecimal("50.00"), accounts.stream().filter(item -> item.accountId().equals(first.id()))
                    .findFirst().orElseThrow().income());
            assertEquals(0, accounts.stream().filter(item -> item.accountId().equals(second.id()))
                    .findFirst().orElseThrow().income().signum());
        }
        assertEquals(new BigDecimal("125.00"), insights.totalExpense());
        assertEquals(new BigDecimal("80.00"), insights.accounts().getFirst().amount());
        assertEquals("bank", insights.accounts().getFirst().accountType());
        assertEquals(new BigDecimal("30.00"), insights.accounts().get(1).amount());
        assertEquals("打车", insights.transactions().getFirst().note());
        assertEquals(new BigDecimal("45.00"), insights.categories().stream()
                .filter(item -> item.category().equals("餐饮")).findFirst().orElseThrow().amount());
        assertEquals(0, financeService.getExpenseInsights("other-insights-user", "month").totalExpense().signum());
        assertThrows(IllegalArgumentException.class, () -> financeService.getExpenseInsights(owner, "decade"));
    }

    @Test
    void expenseCannotOverdrawAccount() {
        FinanceAccount account = financeService.createAccount(
                "finance-overdraft-user", "现金", "cash", "CNY", new BigDecimal("20.00"), false, BigDecimal.ZERO);

        assertThrows(IllegalArgumentException.class, () -> financeService.createTransaction(
                "finance-overdraft-user", account.id(), "expense", "其他", "超额支出",
                new BigDecimal("20.01"), LocalDate.now(), LocalTime.NOON));
        assertEquals(new BigDecimal("20.00"), financeService.getOverview("finance-overdraft-user").totalAssets());
    }

    @Test
    void balanceAdjustmentsCreateAuditTransactionsWithoutChangingIncomeOrExpenseTotals() {
        String ownerId = "finance-adjustment-user";
        FinanceAccount account = financeService.createAccount(
                ownerId, "银行卡", "bank", "CNY", new BigDecimal("100.00"), false, BigDecimal.ZERO);

        var increase = financeService.adjustAccountBalance(
                ownerId, account.id(), "increase", new BigDecimal("25.00"), "对账补差");
        var decrease = financeService.adjustAccountBalance(
                ownerId, account.id(), "decrease", new BigDecimal("40.00"), "修正重复录入余额");

        FinanceOverview overview = financeService.getOverview(ownerId);
        ExpenseChart chart = financeService.getExpenseChart(ownerId, "month");
        assertEquals(new BigDecimal("85.00"), overview.totalAssets());
        assertEquals(0, overview.monthIncome().compareTo(BigDecimal.ZERO));
        assertEquals(0, overview.monthExpense().compareTo(BigDecimal.ZERO));
        assertEquals(0, chart.totalIncome().compareTo(BigDecimal.ZERO));
        assertEquals(0, chart.totalExpense().compareTo(BigDecimal.ZERO));
        assertEquals("adjustment_increase", increase.transactionType());
        assertEquals("adjustment_decrease", decrease.transactionType());
        assertEquals("adjustment", increase.source());
        assertEquals("余额校准", increase.category());
        assertEquals("对账补差", increase.note());
        var summary = financeService.getCashflowSummary(ownerId, LocalDate.now(), LocalDate.now());
        assertEquals(0, summary.income().signum());
        assertEquals(0, summary.expense().signum());
        assertEquals(2, financeService.getDayTransactions(ownerId, LocalDate.now()).size());
        assertEquals(0, financeService.getDayTransactions("unrelated-owner", LocalDate.now()).size());
        assertEquals(0, financeService.getDayTransactions(ownerId, LocalDate.now().minusDays(1)).size());
    }

    @Test
    void balanceAdjustmentRequiresNoteAndCannotReduceBelowZero() {
        String ownerId = "finance-adjustment-validation-user";
        FinanceAccount account = financeService.createAccount(
                ownerId, "现金", "cash", "CNY", new BigDecimal("20.00"), false, BigDecimal.ZERO);

        var noteError = assertThrows(IllegalArgumentException.class, () -> financeService.adjustAccountBalance(
                ownerId, account.id(), "increase", BigDecimal.ONE, " "));
        var balanceError = assertThrows(IllegalArgumentException.class, () -> financeService.adjustAccountBalance(
                ownerId, account.id(), "decrease", new BigDecimal("20.01"), "现金盘点"));

        assertEquals("资产调整备注不能为空", noteError.getMessage());
        assertEquals("账户余额不足，无法完成手动减少", balanceError.getMessage());
        FinanceOverview overview = financeService.getOverview(ownerId);
        assertEquals(new BigDecimal("20.00"), overview.totalAssets());
        assertEquals(0, overview.transactions().size());
    }

    @Test
    void editingExpenseRestoresOriginalAccountAndAppliesChangesToNewAccount() {
        String ownerId = "finance-edit-expense-user";
        LocalDate correctedDate = LocalDate.now().minusDays(1);
        FinanceAccount originalAccount = financeService.createAccount(
                ownerId, "银行卡", "bank", "CNY", new BigDecimal("100.00"), false, BigDecimal.ZERO);
        FinanceAccount correctedAccount = financeService.createAccount(
                ownerId, "微信零钱", "wechat_balance", "CNY", new BigDecimal("50.00"), false, BigDecimal.ZERO);
        var transaction = financeService.createTransaction(
                ownerId, originalAccount.id(), "expense", "餐饮", "午餐", new BigDecimal("30.00"),
                LocalDate.now(), LocalTime.NOON);

        var updated = financeService.updateTransaction(
                ownerId, transaction.id(), correctedAccount.id(), "expense", "交通", "打车",
                new BigDecimal("20.00"), correctedDate, LocalTime.of(18, 30));

        FinanceOverview overview = financeService.getOverview(ownerId);
        var restoredAccount = overview.accounts().stream()
                .filter(account -> account.id().equals(originalAccount.id())).findFirst().orElseThrow();
        var chargedAccount = overview.accounts().stream()
                .filter(account -> account.id().equals(correctedAccount.id())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("100.00"), restoredAccount.balance());
        assertEquals(new BigDecimal("30.00"), chargedAccount.balance());
        assertEquals(correctedAccount.id(), updated.accountId());
        assertEquals(correctedDate, updated.date());
        assertEquals("交通", updated.category());
        assertEquals("打车", updated.note());
        assertEquals(new BigDecimal("20.00"), updated.amount());
    }

    @Test
    void failedAccountChangeRollsBackEveryBalanceAdjustment() {
        String ownerId = "finance-edit-rollback-user";
        FinanceAccount originalAccount = financeService.createAccount(
                ownerId, "原账户", "bank", "CNY", BigDecimal.ZERO, false, BigDecimal.ZERO);
        FinanceAccount targetAccount = financeService.createAccount(
                ownerId, "目标账户", "wechat_balance", "CNY", BigDecimal.ZERO, false, BigDecimal.ZERO);
        var income = financeService.createTransaction(
                ownerId, originalAccount.id(), "income", "工资", "收入", new BigDecimal("100.00"),
                LocalDate.now(), LocalTime.of(9, 0));
        financeService.createTransaction(
                ownerId, originalAccount.id(), "expense", "日常", "支出", new BigDecimal("80.00"),
                LocalDate.now(), LocalTime.of(10, 0));

        var error = assertThrows(IllegalArgumentException.class, () -> financeService.updateTransaction(
                ownerId, income.id(), targetAccount.id(), "income", "工资", "收入",
                new BigDecimal("100.00"), LocalDate.now(), LocalTime.of(9, 0)));

        assertEquals("原账户余额不足，无法撤销原流水", error.getMessage());
        FinanceOverview overview = financeService.getOverview(ownerId);
        assertEquals(new BigDecimal("20.00"), overview.accounts().stream()
                .filter(account -> account.id().equals(originalAccount.id())).findFirst().orElseThrow().balance());
        assertEquals(BigDecimal.ZERO.setScale(2), overview.accounts().stream()
                .filter(account -> account.id().equals(targetAccount.id())).findFirst().orElseThrow().balance());
        assertEquals(originalAccount.id(), financeService.getTransactions(ownerId).stream()
                .filter(item -> item.id().equals(income.id())).findFirst().orElseThrow().accountId());
    }

    @Test
    void supportedAccountCategoriesCanBeCreated() {
        List<String> accountTypes = List.of(
                "wechat_balance", "wechat_yield", "alipay_balance", "alipay_yuebao", "bank", "other");

        for (String accountType : accountTypes) {
            FinanceAccount account = financeService.createAccount(
                    "finance-account-types-user", accountType, accountType, "CNY",
                    BigDecimal.ZERO, false, BigDecimal.ZERO);
            assertEquals(accountType, account.accountType());
        }
    }

    @Test
    void interestAccountCreatesOneAutomaticIncomePerDay() {
        FinanceAccount account = financeService.createAccount(
                "finance-yield-user", "余额宝", "alipay", "CNY", new BigDecimal("36500.00"),
                true, new BigDecimal("1.000000"));

        financeService.settleYieldThrough("finance-yield-user", LocalDate.now().plusDays(1));
        FinanceOverview overview = financeService.getOverview("finance-yield-user");

        assertEquals(new BigDecimal("36501.00"), overview.totalAssets());
        assertEquals(1, overview.transactions().size());
        assertEquals("yield", overview.transactions().getFirst().transactionType());
        assertEquals("automatic", overview.transactions().getFirst().source());
        assertEquals(new BigDecimal("1.00"), overview.transactions().getFirst().amount());
    }

    private static Path createDatabasePath() {
        try {
            return Files.createTempDirectory("butvan-finance-test-").resolve("butvan.db");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建财务测试数据库", exception);
        }
    }

    @Test
    void categoryColorsPersistAndRemainIsolatedByOwnerAndTransactionType() {
        String owner = "finance-category-colors";
        var account = financeService.createAccount(owner, "日常", "bank", "CNY", new BigDecimal("100.00"), false, BigDecimal.ZERO);
        var expense = financeService.createTransaction(owner, account.id(), "expense", " 宠物 ", "猫粮",
                new BigDecimal("10.00"), LocalDate.now(), LocalTime.NOON, "#a855f7");
        financeService.createTransaction(owner, account.id(), "income", "宠物", "寄养收入",
                new BigDecimal("5.00"), LocalDate.now(), LocalTime.NOON, "#06b6d4");
        // 不传颜色的旧入口（如 Agent 工具）不能覆盖用户已选颜色。
        financeService.createTransaction(owner, account.id(), "expense", "宠物", "零食",
                BigDecimal.ONE, LocalDate.now(), LocalTime.NOON);
        assertEquals("#A855F7", financeService.getTransactionCategoryColors(owner).get("expense").get("宠物"));
        assertEquals("#06B6D4", financeService.getTransactionCategoryColors(owner).get("income").get("宠物"));

        String other = "finance-category-colors-other";
        var otherAccount = financeService.createAccount(other, "日常", "bank", "CNY", new BigDecimal("100.00"), false, BigDecimal.ZERO);
        financeService.createTransaction(other, otherAccount.id(), "expense", "宠物", "猫粮",
                BigDecimal.ONE, LocalDate.now(), LocalTime.NOON, "#F97316");
        assertEquals("#F97316", financeService.getTransactionCategoryColors(other).get("expense").get("宠物"));
        assertEquals("#A855F7", financeService.getTransactionCategoryColors(owner).get("expense").get("宠物"));

        financeService.updateTransaction(owner, expense.id(), account.id(), "expense", "宠物", "更新猫粮",
                new BigDecimal("11.00"), LocalDate.now(), LocalTime.NOON, "#3b82f6");
        assertEquals("#3B82F6", financeService.getTransactionCategoryColors(owner).get("expense").get("宠物"));
        assertEquals("#3B82F6", financeService.getExpenseChart(owner, "month").categoryColors().get("宠物"));
        assertEquals("#3B82F6", financeService.getExpenseChart(owner, "year").categoryColors().get("宠物"));
        assertEquals("#06B6D4", financeService.getTransactionCategoryColors(owner).get("income").get("宠物"));
    }

    @Test
    void invalidColorAndFailedTransactionLeaveColorAndBalanceUnchanged() {
        String owner = "finance-invalid-category-color";
        var account = financeService.createAccount(owner, "日常", "bank", "CNY", new BigDecimal("100.00"), false, BigDecimal.ZERO);
        var expense = financeService.createTransaction(owner, account.id(), "expense", "餐饮", "午餐",
                new BigDecimal("10.00"), LocalDate.now(), LocalTime.NOON, "#F59E0B");
        for (String invalid : List.of("red", "#abc", "#12345678", "#ZZZZZZ", "", "url(test)")) {
            assertThrows(IllegalArgumentException.class, () -> financeService.createTransaction(
                    owner, account.id(), "expense", "不应保存", "无效配色", BigDecimal.ONE,
                    LocalDate.now(), LocalTime.NOON, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> financeService.updateTransaction(
                owner, expense.id(), account.id(), "expense", "餐饮", "余额不足",
                new BigDecimal("1000.00"), LocalDate.now(), LocalTime.NOON, "#3B82F6"));
        assertThrows(IllegalArgumentException.class, () -> financeService.createTransaction(
                owner, account.id(), "expense", "不应保存", "余额不足", new BigDecimal("1000.00"),
                LocalDate.now(), LocalTime.NOON, "#EC4899"));
        assertEquals(1, financeService.getTransactions(owner).size());
        assertEquals(new BigDecimal("90.00"), financeService.getOverview(owner).totalAssets());
        assertEquals(List.of("餐饮"), financeService.getTransactionCategories(owner).get("expense"));
        assertEquals("#F59E0B", financeService.getTransactionCategoryColors(owner).get("expense").get("餐饮"));
    }
}
