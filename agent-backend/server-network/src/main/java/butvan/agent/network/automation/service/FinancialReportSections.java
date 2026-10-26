package butvan.agent.network.automation.service;

import butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.finance.service.FinanceService.ReportAsset;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;

/** 报告财务明细：按币种排名、分类汇总与稳定账户 ID 对比，不判断消费好坏。 */
@Slf4j
public final class FinancialReportSections {
    private FinancialReportSections() {}

    /** 比较本期末与上期末余额；两侧独立降级，缺失账户不补零。 */
    public static TaskReportBuilder.Section assets(FinanceService finance, String owner,
                                                   LocalDate currentDate, LocalDate previousDate, ZoneId zone) {
        List<ReportAsset> current = readAssets(finance, owner, currentDate, zone);
        List<ReportAsset> previous = readAssets(finance, owner, previousDate, zone);
        Map<String, ReportAsset> a = index(current), b = index(previous);
        Set<String> ids = new LinkedHashSet<>(a.keySet());
        ids.addAll(b.keySet());
        List<TaskReportBuilder.Metric> metrics = new ArrayList<>();
        for (String id : ids) {
            ReportAsset x = a.get(id), y = b.get(id), label = x == null ? y : x;
            String change = current == null || previous == null ? "无法比较"
                    : x == null ? "本期未出现（不等于亏损）" : y == null ? "新增账户（不等于收入）"
                    : !x.currency().equals(y.currency()) ? "币种变化，不比较"
                    : delta(x.balance(), y.balance());
            metrics.add(new TaskReportBuilder.Metric(label.name() + " · " + label.currency() + " · " + label.id().substring(0, Math.min(8, label.id().length())),
                    x == null ? current == null ? "不可用" : "未出现" : money(x.balance()),
                    y == null ? previous == null ? "不可用" : "未出现" : money(y.balance()), change));
        }
        if (current != null && previous != null) {
            Map<String, BigDecimal> totalsA = totals(current), totalsB = totals(previous);
            Set<String> currencies = new TreeSet<>(totalsA.keySet());
            currencies.addAll(totalsB.keySet());
            for (String currency : currencies) {
                BigDecimal x = totalsA.getOrDefault(currency, BigDecimal.ZERO), y = totalsB.getOrDefault(currency, BigDecimal.ZERO);
                metrics.add(0, new TaskReportBuilder.Metric("资产合计 · " + currency, money(x), money(y), delta(x, y)));
            }
        }
        String detail = "期末口径：" + currentDate + " 对比 " + previousDate + "。\n"
                + "按当前账本回溯日末余额，名称使用当前名称，收益只包含已入账部分；回填或编辑历史流水会改变回溯结果。\n"
                + "账户创建前标为未出现；新增初始资金、转账与余额校准会影响资产，但不属于真实收入或支出。\n"
                + "当前没有账户删除接口与历史快照，已从数据库移除的账户无法还原；不将缺失记录判为零余额或亏损。\n"
                + (current == null ? "本期资产暂不可用。\n" : "")
                + (previous == null ? "上期资产暂不可用。\n" : "")
                + (ids.isEmpty() && current != null && previous != null ? "两个期末均无可回溯账户。\n" : "");
        StringBuilder text = new StringBuilder(detail);
        metrics.forEach(m -> text.append(m.label()).append("：本期 ").append(m.current()).append(" · 上期 ")
                .append(m.previous()).append(" · ").append(m.change()).append('\n'));
        return new TaskReportBuilder.Section("assets", "资产变化", text.toString(), List.copyOf(metrics), detail);
    }

    private static List<ReportAsset> readAssets(FinanceService finance, String owner, LocalDate date, ZoneId zone) {
        try { return finance.getReportAssets(owner, date, zone); }
        catch (RuntimeException e) {
            log.warn("报告资产回溯失败 date={} type={}", date, e.getClass().getSimpleName());
            return null;
        }
    }

    private static Map<String, ReportAsset> index(List<ReportAsset> items) {
        Map<String, ReportAsset> result = new LinkedHashMap<>();
        if (items != null) items.forEach(item -> result.put(item.id(), item));
        return result;
    }

    private static Map<String, BigDecimal> totals(List<ReportAsset> items) {
        Map<String, BigDecimal> result = new TreeMap<>();
        items.forEach(item -> result.merge(item.currency(), item.balance(), BigDecimal::add));
        return result;
    }

    /** 支出排行覆盖财务与旧日历投影，同币种内比较，明细有界。 */
    public static String expenseDetails(List<ExpenseEntry> entries, int limit) {
        return ranked(entries, ExpenseEntry::currency, ExpenseEntry::amount,
                item -> item.date() + " · " + item.category() + " · " + excerpt(item.note()), limit, "大额支出")
                + categories(entries, ExpenseEntry::currency, ExpenseEntry::amount, ExpenseEntry::category, limit);
    }

    /** 收入排行包含已入账收益，明确收益来源，不把划账与校准算成收入。 */
    public static String incomeDetails(List<FinanceTransaction> entries, int limit) {
        return "真实收入含已入账收益，不含划账与余额校准。\n"
                + ranked(entries, FinanceTransaction::currency, FinanceTransaction::amount,
                item -> item.date() + " · " + item.accountName() + " · " + item.category()
                        + ("yield".equals(item.transactionType()) ? "（自动收益）" : "") + " · " + excerpt(item.note()), limit, "大额收入")
                + categories(entries, FinanceTransaction::currency, FinanceTransaction::amount, FinanceTransaction::category, limit);
    }

    private static <T> String ranked(List<T> entries, Function<T, String> currency, Function<T, BigDecimal> amount,
                                     Function<T, String> label, int limit, String title) {
        Map<String, List<T>> groups = new TreeMap<>();
        entries.forEach(item -> groups.computeIfAbsent(currency.apply(item), ignored -> new ArrayList<>()).add(item));
        StringBuilder text = new StringBuilder();
        groups.forEach((unit, items) -> {
            items.sort(Comparator.comparing(amount).reversed().thenComparing(label));
            text.append(title).append(" TOP ").append(limit).append(" · ").append(unit).append("（共 ").append(items.size()).append(" 笔）\n");
            items.stream().limit(limit).forEach(item -> text.append("• ").append(label.apply(item))
                    .append(" · ").append(money(amount.apply(item))).append(' ').append(unit).append('\n'));
            if (items.size() > limit) {
                BigDecimal rest = items.stream().skip(limit).map(amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                text.append("其余 ").append(items.size() - limit).append(" 笔合计 ").append(money(rest)).append(' ').append(unit).append('\n');
            }
        });
        return text.toString();
    }

    private static <T> String categories(List<T> entries, Function<T, String> currency, Function<T, BigDecimal> amount,
                                         Function<T, String> category, int limit) {
        Map<String, Map<String, BigDecimal>> groups = new TreeMap<>();
        entries.forEach(item -> groups.computeIfAbsent(currency.apply(item), ignored -> new TreeMap<>())
                .merge(category.apply(item) == null ? "未分类" : category.apply(item), amount.apply(item), BigDecimal::add));
        StringBuilder text = new StringBuilder();
        groups.forEach((unit, categories) -> {
            BigDecimal total = categories.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            text.append("分类构成 · ").append(unit).append("（按金额，最多 ").append(limit).append(" 类）\n");
            categories.entrySet().stream().sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey())).limit(limit).forEach(item -> text.append("• ")
                    .append(item.getKey()).append("：").append(money(item.getValue())).append(' ').append(unit)
                    .append(" · ").append(total.signum() == 0 ? "0" : item.getValue().multiply(BigDecimal.valueOf(100))
                            .divide(total, 1, RoundingMode.HALF_UP).toPlainString()).append("%\n"));
        });
        return text.toString();
    }

    private static String excerpt(String text) {
        if (text == null || text.isBlank()) return "无备注";
        String value = text.replaceAll("\\s+", " ");
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }
    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String delta(BigDecimal a, BigDecimal b) {
        BigDecimal change = a.subtract(b);
        return change.signum() == 0 ? "持平" : (change.signum() > 0 ? "+" : "") + money(change);
    }
}
