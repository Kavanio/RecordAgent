package butvan.agent.network.finance.service;

import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.finance.model.FinanceModels.ExpenseCategoryAmount;
import butvan.agent.network.finance.model.FinanceModels.ExpenseChart;
import butvan.agent.network.finance.model.FinanceModels.ExpenseChartDay;
import butvan.agent.network.finance.model.FinanceModels.ExpenseInsights;
import butvan.agent.network.finance.model.FinanceModels.RankedExpense;
import butvan.agent.network.finance.model.FinanceModels.FinanceAccount;
import butvan.agent.network.finance.model.FinanceModels.CashflowSummary;
import butvan.agent.network.finance.model.FinanceModels.FinanceOverview;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import butvan.agent.network.finance.repository.FinanceRepository;
import butvan.agent.network.finance.repository.FinanceRepository.DailyIncomeTotal;
import butvan.agent.network.finance.repository.FinanceRepository.MonthTotals;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 资产账户、手工流水与每日收益计提的领域服务。 */
@Service
@RequiredArgsConstructor
public class FinanceService {

    /** 日报使用的当前资产余额与指定自然日账户流水。 */
    public record DailyAssetData(List<FinanceAccount> accounts, List<FinanceTransaction> transactions) {}

    private static final Set<String> ACCOUNT_TYPES = Set.of(
            "wechat_balance", "wechat_yield", "alipay_balance", "alipay_yuebao", "bank", "other",
            "wechat", "alipay", "cash");
    private static final Set<String> TRANSACTION_TYPES = Set.of("income", "expense");
    private static final Set<String> ADJUSTMENT_DIRECTIONS = Set.of("increase", "decrease");
    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("36500");

    private final FinanceRepository repository;
    private final ExpenseAnalyticsService expenseAnalyticsService;

    /** 查询财务总览；读取前先补齐所有生息账户截至今日的收益。 */
    @Transactional
    public FinanceOverview getOverview(String ownerId) {
        requireOwner(ownerId);
        LocalDate today = LocalDate.now();
        settleYieldThrough(ownerId, today);
        List<FinanceAccount> accounts = repository.findAccounts(ownerId);
        BigDecimal totalAssets = accounts.stream()
                .map(FinanceAccount::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate monthStart = today.withDayOfMonth(1);
        MonthTotals totals = repository.summarizeMonth(ownerId, monthStart, monthStart.plusMonths(1));
        BigDecimal monthExpense = expenseAnalyticsService
                .analyze(ownerId, monthStart, monthStart.plusMonths(1).minusDays(1)).totalExpense();
        return new FinanceOverview(totalAssets, totals.income(), monthExpense, totals.yield(),
                accounts, findRecentTransactions(ownerId));
    }

    /** 按本周、本月或今年返回连续日期的收支趋势和支出分类堆叠数据。 */
    @Transactional
    public ExpenseChart getExpenseChart(String ownerId, String range) {
        requireOwner(ownerId);
        String normalizedRange = range == null || range.isBlank() ? "month" : range.trim().toLowerCase();
        LocalDate today = LocalDate.now();
        LocalDate from = chartStart(normalizedRange, today);
        settleYieldThrough(ownerId, today);
        var analysis = expenseAnalyticsService.analyze(ownerId, from, today);
        Map<LocalDate, BigDecimal> incomeByDate = repository
                .findDailyIncomeTotals(ownerId, from, today.plusDays(1)).stream()
                .collect(Collectors.toMap(DailyIncomeTotal::date, DailyIncomeTotal::amount));
        List<ExpenseChartDay> days = analysis.days().stream().map(day -> new ExpenseChartDay(
                day.date(), day.total(), incomeByDate.getOrDefault(day.date(), BigDecimal.ZERO),
                day.categories().entrySet().stream()
                .map(item -> new ExpenseCategoryAmount(item.getKey(), item.getValue())).toList())).toList();
        BigDecimal totalIncome = incomeByDate.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExpenseChart(normalizedRange, from, today, analysis.totalExpense(), totalIncome, days,
                repository.findTransactionCategoryColors(ownerId, "expense"));
    }

    /** 补齐收益后查询期间内各账户收支，以及全口径支出排行。 */
    @Transactional
    public ExpenseInsights getExpenseInsights(String ownerId, String range) {
        requireOwner(ownerId);
        String normalizedRange = range == null || range.isBlank() ? "month" : range.trim().toLowerCase();
        if (!Set.of("week", "month", "year").contains(normalizedRange)) {
            throw new IllegalArgumentException("支出分析时间范围仅支持 week、month 或 year");
        }
        LocalDate today = LocalDate.now();
        LocalDate from = chartStart(normalizedRange, today);
        settleYieldThrough(ownerId, today);
        var expenses = expenseAnalyticsService.reportExpenses(ownerId, from, today);
        List<RankedExpense> transactions = expenses.stream()
                .sorted(Comparator.comparing(ExpenseAnalyticsService.ExpenseEntry::amount).reversed()
                        .thenComparing(ExpenseAnalyticsService.ExpenseEntry::date, Comparator.reverseOrder())
                        .thenComparing(ExpenseAnalyticsService.ExpenseEntry::id))
                .map(entry -> new RankedExpense(entry.id(), entry.note(), entry.category(),
                        entry.date(), entry.amount(), entry.source())).toList();
        Map<String, BigDecimal> byCategory = expenses.stream().collect(Collectors.toMap(
                ExpenseAnalyticsService.ExpenseEntry::category,
                ExpenseAnalyticsService.ExpenseEntry::amount, BigDecimal::add));
        List<ExpenseCategoryAmount> categories = byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new ExpenseCategoryAmount(entry.getKey(), entry.getValue())).toList();
        BigDecimal total = expenses.stream().map(ExpenseAnalyticsService.ExpenseEntry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ExpenseInsights(normalizedRange, from, today,
                repository.findAccountExpenses(ownerId, from, today), transactions, categories, total);
    }

    private LocalDate chartStart(String range, LocalDate today) {
        return switch (range) {
            case "today" -> today;
            case "week" -> today.minusDays(today.getDayOfWeek().getValue() - 1L);
            case "month" -> today.withDayOfMonth(1);
            case "year" -> today.withDayOfYear(1);
            default -> throw new IllegalArgumentException("图表时间范围仅支持 today、week、month 或 year");
        };
    }

    private List<FinanceTransaction> findRecentTransactions(String ownerId) {
        List<FinanceTransaction> transactions = new ArrayList<>(repository.findTransactions(ownerId, 100));
        expenseAnalyticsService.findRecentCalendarExpenses(ownerId, 100).forEach(expense -> transactions.add(
                new FinanceTransaction("calendar-" + expense.id(), "", "日历记录", expense.date(), expense.time(),
                        "expense", expense.category(), expense.note(), expense.amount(), expense.currency(),
                        "calendar", expense.createdAt())));
        Comparator<FinanceTransaction> newestFirst = Comparator.comparing(FinanceTransaction::date)
                .thenComparing(FinanceTransaction::time)
                .thenComparing(FinanceTransaction::createdAt)
                .reversed();
        return transactions.stream().sorted(newestFirst).limit(100).toList();
    }

    /** 查询完整流水，并合并仍由旧日历入口保存的花销记录。 */
    @Transactional(readOnly = true)
    public List<FinanceTransaction> getTransactions(String ownerId) {
        requireOwner(ownerId);
        List<FinanceTransaction> transactions = new ArrayList<>(repository.findAllTransactions(ownerId));
        expenseAnalyticsService.findAllCalendarExpenses(ownerId).forEach(expense -> transactions.add(
                new FinanceTransaction("calendar-" + expense.id(), "", "日历记录", expense.date(), expense.time(),
                        "expense", expense.category(), expense.note(), expense.amount(), expense.currency(),
                        "calendar", expense.createdAt())));
        Comparator<FinanceTransaction> newestFirst = Comparator.comparing(FinanceTransaction::date)
                .thenComparing(FinanceTransaction::time)
                .thenComparing(FinanceTransaction::createdAt)
                .reversed();
        return transactions.stream().sorted(newestFirst).toList();
    }

    /** 按日读取资产变动，包含划账与校准，不混入无账户的旧日历花销。 */
    @Transactional(readOnly = true)
    public List<FinanceTransaction> getDayTransactions(String ownerId, LocalDate date) {
        requireOwner(ownerId);
        if (date == null) throw new IllegalArgumentException("日期不能为空");
        return repository.findDayTransactions(ownerId, date);
    }

    /** 按日报任务时区结清收益后，读取当前资产余额和指定自然日的账户流水。 */
    @Transactional
    public DailyAssetData getDailyAssetData(String ownerId, LocalDate date) {
        requireOwner(ownerId);
        if (date == null) throw new IllegalArgumentException("日期不能为空");
        settleYieldThrough(ownerId, date);
        return new DailyAssetData(repository.findAccounts(ownerId), repository.findDayTransactions(ownerId, date));
    }

    /** 查询最多一年内的轻量收支汇总，与财务图表复用统计来源。 */
    @Transactional(readOnly = true)
    public CashflowSummary getCashflowSummary(
            String ownerId, LocalDate from, LocalDate to) {
        requireOwner(ownerId);
        if (from == null || to == null || from.isAfter(to)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) {
            throw new IllegalArgumentException("日期范围不合法，最多查询一年");
        }
        BigDecimal income = repository.findDailyIncomeTotals(ownerId, from, to.plusDays(1)).stream()
                .map(DailyIncomeTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CashflowSummary(
                income, expenseAnalyticsService.analyze(ownerId, from, to).totalExpense());
    }

    /** 查询用户使用过的收入与支出分类。 */
    @Transactional(readOnly = true)
    public Map<String, List<String>> getTransactionCategories(String ownerId) {
        requireOwner(ownerId);
        return Map.of(
                "expense", repository.findTransactionCategories(ownerId, "expense"),
                "income", repository.findTransactionCategories(ownerId, "income"));
    }

    /** 查询用户保存的分类颜色；收入与支出使用独立命名空间。 */
    @Transactional(readOnly = true)
    public Map<String, Map<String, String>> getTransactionCategoryColors(String ownerId) {
        requireOwner(ownerId);
        return Map.of(
                "expense", repository.findTransactionCategoryColors(ownerId, "expense"),
                "income", repository.findTransactionCategoryColors(ownerId, "income"));
    }

    /** 创建资产账户；年化率使用百分数表达，例如 1.85 表示 1.85%。 */
    @Transactional
    public FinanceAccount createAccount(
            String ownerId, String name, String accountType, String currency, BigDecimal initialBalance,
            boolean interestEnabled, BigDecimal annualRatePercent) {
        requireOwner(ownerId);
        if (name == null || name.isBlank()) throw new IllegalArgumentException("账户名称不能为空");
        if (!ACCOUNT_TYPES.contains(accountType)) throw new IllegalArgumentException("账户类型不合法");
        String normalizedCurrency = normalizeCurrency(currency);
        BigDecimal balance = requireMoney(initialBalance, true, "初始余额");
        BigDecimal rate = annualRatePercent == null ? BigDecimal.ZERO : annualRatePercent;
        if (rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0 || rate.scale() > 6) {
            throw new IllegalArgumentException("年化收益率必须在 0% 到 100% 之间且最多六位小数");
        }
        if (!interestEnabled) rate = BigDecimal.ZERO;
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        repository.insertAccount(id, ownerId, name.trim(), accountType, normalizedCurrency, toMinor(balance),
                interestEnabled, rate.stripTrailingZeros(), LocalDate.now(), now);
        return repository.findAccount(ownerId, id)
                .orElseThrow(() -> new IllegalStateException("账户创建后无法读取"));
    }

    /** 手动校准单个账户余额，并写入不参与真实收支统计的特别流水。 */
    @Transactional
    public FinanceTransaction adjustAccountBalance(
            String ownerId, String accountId, String direction, BigDecimal amount, String note) {
        requireOwner(ownerId);
        if (!ADJUSTMENT_DIRECTIONS.contains(direction)) throw new IllegalArgumentException("资产调整方向不合法");
        if (note == null || note.isBlank()) throw new IllegalArgumentException("资产调整备注不能为空");
        String normalizedNote = note.trim();
        if (normalizedNote.length() > 100) throw new IllegalArgumentException("资产调整备注不能超过 100 个字符");
        BigDecimal normalizedAmount = requireMoney(amount, false, "调整金额");
        FinanceAccount account = repository.findAccount(ownerId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("资产账户不存在"));
        accrueYield(ownerId, account, LocalDate.now());

        long amountMinor = toMinor(normalizedAmount);
        long delta = "decrease".equals(direction) ? -amountMinor : amountMinor;
        Instant now = Instant.now();
        if (!repository.adjustBalance(ownerId, account.id(), delta, now)) {
            throw new IllegalArgumentException("账户余额不足，无法完成手动减少");
        }

        String id = UUID.randomUUID().toString();
        String transactionType = "decrease".equals(direction) ? "adjustment_decrease" : "adjustment_increase";
        LocalDateTime occurredAt = LocalDateTime.now();
        repository.insertTransaction(id, ownerId, account.id(), occurredAt.toLocalDate(), occurredAt.toLocalTime(),
                transactionType, "余额校准", normalizedNote, amountMinor, account.currency(), "adjustment", now);
        return repository.findTransaction(ownerId, id)
                .orElseThrow(() -> new IllegalStateException("资产调整流水创建后无法读取"));
    }

    /** 创建收入或支出流水并同步调整所选账户余额。 */
    @Transactional
    public FinanceTransaction createTransaction(
            String ownerId, String accountId, String transactionType, String category, String note,
            BigDecimal amount, LocalDate date, LocalTime time) {
        return createTransaction(ownerId, accountId, transactionType, category, note, amount, date, time, null);
    }

    /** 创建流水并原子保存可选分类颜色；旧调用省略颜色时沿用已有配色。 */
    @Transactional
    public FinanceTransaction createTransaction(
            String ownerId, String accountId, String transactionType, String category, String note,
            BigDecimal amount, LocalDate date, LocalTime time, String categoryColor) {
        requireOwner(ownerId);
        String normalizedColor = normalizeCategoryColor(categoryColor);
        if (!TRANSACTION_TYPES.contains(transactionType)) throw new IllegalArgumentException("流水类型不合法");
        if (category == null || category.isBlank()) throw new IllegalArgumentException("分类不能为空");
        String normalizedCategory = category.trim();
        if (normalizedCategory.length() > 40) throw new IllegalArgumentException("分类不能超过 40 个字符");
        if (note == null || note.isBlank()) throw new IllegalArgumentException("说明不能为空");
        if (date == null || time == null) throw new IllegalArgumentException("流水日期和时间不能为空");
        BigDecimal normalizedAmount = requireMoney(amount, false, "流水金额");
        FinanceAccount account = repository.findAccount(ownerId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("资产账户不存在"));
        accrueYield(ownerId, account, LocalDate.now());
        long amountMinor = toMinor(normalizedAmount);
        long delta = "expense".equals(transactionType) ? -amountMinor : amountMinor;
        Instant now = Instant.now();
        if (!repository.adjustBalance(ownerId, accountId, delta, now)) {
            throw new IllegalArgumentException("账户余额不足，无法记录这笔支出");
        }
        String id = UUID.randomUUID().toString();
        repository.rememberTransactionCategory(ownerId, transactionType, normalizedCategory, now, normalizedColor);
        repository.insertTransaction(id, ownerId, accountId, date, time, transactionType,
                normalizedCategory, note.trim(), amountMinor, account.currency(), "manual", now);
        return repository.findTransactions(ownerId, 100).stream()
                .filter(transaction -> transaction.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("流水创建后无法读取"));
    }

    /**
     * 修改单条手工收入或支出，并在同一事务内撤销旧余额影响后应用新余额影响。
     * 自动收益、划账及日历兼容记录不允许通过该入口修改。
     */
    @Transactional
    public FinanceTransaction updateTransaction(
            String ownerId, String transactionId, String accountId, String transactionType, String category,
            String note, BigDecimal amount, LocalDate date, LocalTime time) {
        return updateTransaction(ownerId, transactionId, accountId, transactionType, category, note, amount, date, time, null);
    }

    /** 修改流水与同名分类配色；余额、流水和颜色任一校验失败时整体回滚。 */
    @Transactional
    public FinanceTransaction updateTransaction(
            String ownerId, String transactionId, String accountId, String transactionType, String category,
            String note, BigDecimal amount, LocalDate date, LocalTime time, String categoryColor) {
        requireOwner(ownerId);
        String normalizedColor = normalizeCategoryColor(categoryColor);
        if (transactionId == null || transactionId.isBlank()) throw new IllegalArgumentException("流水不能为空");
        if (!TRANSACTION_TYPES.contains(transactionType)) throw new IllegalArgumentException("流水类型不合法");
        if (category == null || category.isBlank()) throw new IllegalArgumentException("分类不能为空");
        String normalizedCategory = category.trim();
        if (normalizedCategory.length() > 40) throw new IllegalArgumentException("分类不能超过 40 个字符");
        if (note == null || note.isBlank()) throw new IllegalArgumentException("说明不能为空");
        if (date == null || time == null) throw new IllegalArgumentException("流水日期和时间不能为空");
        BigDecimal normalizedAmount = requireMoney(amount, false, "流水金额");

        FinanceTransaction existing = repository.findTransaction(ownerId, transactionId.trim())
                .orElseThrow(() -> new IllegalArgumentException("财务流水不存在"));
        if (!"manual".equals(existing.source()) || !TRANSACTION_TYPES.contains(existing.transactionType())) {
            throw new IllegalArgumentException("仅支持修改手工收入或支出流水");
        }
        FinanceAccount oldAccount = repository.findAccount(ownerId, existing.accountId())
                .orElseThrow(() -> new IllegalStateException("原资产账户不存在"));
        FinanceAccount newAccount = repository.findAccount(ownerId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("资产账户不存在"));

        LocalDate today = LocalDate.now();
        accrueYield(ownerId, oldAccount, today);
        if (!oldAccount.id().equals(newAccount.id())) accrueYield(ownerId, newAccount, today);

        long oldAmountMinor = toMinor(existing.amount());
        long newAmountMinor = toMinor(normalizedAmount);
        long reversalDelta = "expense".equals(existing.transactionType()) ? oldAmountMinor : -oldAmountMinor;
        long replacementDelta = "expense".equals(transactionType) ? -newAmountMinor : newAmountMinor;
        Instant now = Instant.now();
        if (oldAccount.id().equals(newAccount.id())) {
            long combinedDelta = Math.addExact(reversalDelta, replacementDelta);
            if (combinedDelta != 0 && !repository.adjustBalance(ownerId, oldAccount.id(), combinedDelta, now)) {
                throw new IllegalArgumentException("修改后账户余额不足，无法保存");
            }
        } else {
            if (!repository.adjustBalance(ownerId, oldAccount.id(), reversalDelta, now)) {
                throw new IllegalArgumentException("原账户余额不足，无法撤销原流水");
            }
            if (!repository.adjustBalance(ownerId, newAccount.id(), replacementDelta, now)) {
                throw new IllegalArgumentException("新账户余额不足，无法保存这笔支出");
            }
        }

        repository.rememberTransactionCategory(ownerId, transactionType, normalizedCategory, now, normalizedColor);
        repository.updateTransaction(ownerId, existing.id(), newAccount.id(), date, time, transactionType,
                normalizedCategory, note.trim(), newAmountMinor, newAccount.currency());
        return repository.findTransaction(ownerId, existing.id())
                .orElseThrow(() -> new IllegalStateException("流水修改后无法读取"));
    }

    /** 只接受六位十六进制颜色；null 表示调用方未修改分类颜色。 */
    private String normalizeCategoryColor(String color) {
        if (color == null) return null;
        if (!color.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("分类颜色必须为 #RRGGBB 格式");
        return color.toUpperCase(Locale.ROOT);
    }

    /** 在两个资产账户之间进行划账，并成对写入划出与划入流水。 */
    @Transactional
    public List<FinanceTransaction> transfer(
            String ownerId, String fromAccountId, String toAccountId, BigDecimal amount,
            String note, LocalDate date, LocalTime time) {
        requireOwner(ownerId);
        if (fromAccountId == null || fromAccountId.isBlank()) throw new IllegalArgumentException("转出账户不能为空");
        if (toAccountId == null || toAccountId.isBlank()) throw new IllegalArgumentException("转入账户不能为空");
        if (fromAccountId.trim().equals(toAccountId.trim())) {
            throw new IllegalArgumentException("转出账户与转入账户不能相同");
        }
        if (date == null || time == null) throw new IllegalArgumentException("流水日期和时间不能为空");
        BigDecimal normalizedAmount = requireMoney(amount, false, "划账金额");

        FinanceAccount fromAccount = repository.findAccount(ownerId, fromAccountId.trim())
                .orElseThrow(() -> new IllegalArgumentException("转出账户不存在"));
        FinanceAccount toAccount = repository.findAccount(ownerId, toAccountId.trim())
                .orElseThrow(() -> new IllegalArgumentException("转入账户不存在"));

        if (!fromAccount.currency().equalsIgnoreCase(toAccount.currency())) {
            throw new IllegalArgumentException("不同币种账户之间暂不支持划账");
        }

        LocalDate today = LocalDate.now();
        accrueYield(ownerId, fromAccount, today);
        accrueYield(ownerId, toAccount, today);

        long amountMinor = toMinor(normalizedAmount);
        Instant now = Instant.now();

        if (!repository.adjustBalance(ownerId, fromAccount.id(), -amountMinor, now)) {
            throw new IllegalArgumentException("转出账户余额不足，无法划账");
        }
        repository.adjustBalance(ownerId, toAccount.id(), amountMinor, now);

        String trimmedNote = note != null ? note.trim() : "";
        if (trimmedNote.length() > 100) throw new IllegalArgumentException("说明不能超过 100 个字符");
        String outNote = !trimmedNote.isEmpty() ? trimmedNote : "划账至 " + toAccount.name();
        String inNote = !trimmedNote.isEmpty() ? trimmedNote : "从 " + fromAccount.name() + " 划入";

        String fromTransactionId = UUID.randomUUID().toString();
        String toTransactionId = UUID.randomUUID().toString();

        repository.insertTransaction(fromTransactionId, ownerId, fromAccount.id(), date, time,
                "transfer_out", "划账", outNote, amountMinor, fromAccount.currency(), "manual", now);
        repository.insertTransaction(toTransactionId, ownerId, toAccount.id(), date, time,
                "transfer_in", "划账", inNote, amountMinor, toAccount.currency(), "manual", now);

        FinanceTransaction fromTx = new FinanceTransaction(fromTransactionId, fromAccount.id(), fromAccount.name(),
                date, time, "transfer_out", "划账", outNote, normalizedAmount, fromAccount.currency(),
                "manual", now);

        FinanceTransaction toTx = new FinanceTransaction(toTransactionId, toAccount.id(), toAccount.name(),
                date, time, "transfer_in", "划账", inNote, normalizedAmount, toAccount.currency(),
                "manual", now);

        return List.of(fromTx, toTx);
    }

    /** 补齐指定日期前尚未计提的账户收益，供总览读取和未来定时任务复用。 */
    @Transactional
    public void settleYieldThrough(String ownerId, LocalDate throughDate) {
        requireOwner(ownerId);
        if (throughDate == null) throw new IllegalArgumentException("收益计提日期不能为空");
        repository.findAccounts(ownerId).forEach(account -> accrueYield(ownerId, account, throughDate));
    }

    private void accrueYield(String ownerId, FinanceAccount account, LocalDate today) {
        if (!account.interestEnabled() || account.annualRatePercent().signum() <= 0
                || !account.lastAccrualDate().isBefore(today)) return;
        BigDecimal runningBalance = account.balance();
        LocalDate date = account.lastAccrualDate().plusDays(1);
        Instant now = Instant.now();
        while (!date.isAfter(today)) {
            BigDecimal yield = runningBalance.multiply(account.annualRatePercent())
                    .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_UP);
            if (yield.signum() > 0) {
                long yieldMinor = toMinor(yield);
                repository.insertTransaction(UUID.randomUUID().toString(), ownerId, account.id(), date,
                        LocalTime.of(0, 0), "yield", "理财收益", account.name() + " 每日收益",
                        yieldMinor, account.currency(), "automatic", now);
                runningBalance = runningBalance.add(yield);
            }
            date = date.plusDays(1);
        }
        repository.updateBalanceAndAccrualDate(ownerId, account.id(), toMinor(runningBalance), today, now);
    }

    private BigDecimal requireMoney(BigDecimal value, boolean allowZero, String field) {
        if (value == null || value.scale() > 2 || (allowZero ? value.signum() < 0 : value.signum() <= 0)) {
            throw new IllegalArgumentException(field + (allowZero ? "不能小于零且最多保留两位小数" : "必须大于零且最多保留两位小数"));
        }
        return value;
    }

    private long toMinor(BigDecimal value) {
        return value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private String normalizeCurrency(String currency) {
        String result = currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase();
        if (!result.matches("[A-Z]{3}")) throw new IllegalArgumentException("货币代码不合法");
        return result;
    }

    private void requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("用户不能为空");
    }
}
