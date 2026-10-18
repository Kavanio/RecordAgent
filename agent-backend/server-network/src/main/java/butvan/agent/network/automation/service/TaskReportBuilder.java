package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.TaskSpec;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.study.service.StudyService;
import butvan.agent.network.finance.model.FinanceModels.FinanceAccount;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import butvan.agent.network.finance.service.FinanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Supplier;

/** 从各领域公开 Service 生成完整自然周期报告，各来源独立降级。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskReportBuilder {
    private final DailyEventService daily;
    private final ExpenseAnalyticsService expenses;
    private final StudyService studies;
    private final FinanceService finance;

    public record Metric(String label, String current, String previous, String change) {}
    public record Section(String key, String title, String text, List<Metric> metrics, String detail) {
        public Section(String key, String title, String text) { this(key, title, text, List.of(), text); }
    }
    public record Report(String text, boolean partial, List<Section> sections, String date, String generatedAt) {
        public Report(String text, boolean partial) { this(text, partial, List.of(), "", ""); }
    }
    private record Snapshot(Map<String, BigDecimal> values, String detail,
                            List<ExpenseAnalyticsService.ExpenseEntry> expenseEntries) {
        private Snapshot(Map<String, BigDecimal> values, String detail) { this(values, detail, List.of()); }
    }

    /** 计划时刻决定本期；预览和手动执行由调用方传入当前时刻。 */
    public Report build(String owner, TaskSpec spec, Instant planned, Instant now) {
        if (!spec.isReport()) return new Report(spec.content(), false);
        ZoneId zone = ZoneId.of(spec.timezone());
        ReportPeriod period = ReportPeriod.completed(spec.kind(), planned, zone);
        String generated = now.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "（" + spec.timezone() + "）";
        List<Section> sections = new ArrayList<>();
        StringBuilder text = new StringBuilder(spec.reportLabel() + " · " + period.currentLabel() + "\n对比上期：" + period.previousLabel() + "\n生成于 " + generated + "\n");
        int available = 0;
        boolean partial = false;
        for (String key : List.of("expense", "todo", "study")) {
            if (!(switch (key) { case "expense" -> spec.expense(); case "todo" -> spec.todo(); default -> spec.study(); })) continue;
            Snapshot current = read(key, () -> snapshot(owner, key, period.from(), period.to(), zone));
            Snapshot previous = read(key, () -> snapshot(owner, key, period.previousFrom(), period.previousTo(), zone));
            if (current != null) available++;
            partial |= current == null || previous == null;
            String title = switch (key) {
                case "expense" -> "DAILY_REPORT".equals(spec.kind()) ? "资产与收支" : "支出概览";
                case "todo" -> "待办进度";
                default -> "学习时光";
            };
            List<Metric> metrics = compare(key, current, previous);
            String currentDetail = current == null ? "本期数据暂不可用。\n" : current.detail();
            if ("expense".equals(key) && "DAILY_REPORT".equals(spec.kind()) && current != null) {
                FinanceService.DailyAssetData assetData = readDailyAssetData(owner, period.from());
                if (assetData == null) {
                    currentDetail += "资产余额与账户流水暂不可用。\n";
                    partial = true;
                } else {
                    currentDetail += dailyAssetDetail(assetData, current.expenseEntries());
                }
            }
            StringBuilder body = new StringBuilder(currentDetail);
            if (previous == null) body.append("上期数据暂不可用，无法比较。\n");
            for (Metric metric : metrics) body.append(metric.label()).append("：本期 ").append(metric.current())
                    .append(" · 上期 ").append(metric.previous()).append(" · ").append(metric.change()).append("\n");
            sections.add(new Section(key, title, body.toString(), metrics, current == null
                    ? "本期数据暂不可用。" : currentDetail + (previous == null ? "上期数据暂不可用，无法比较。" : "")));
            text.append("\n").append(title).append("\n").append(body);
        }
        if (available == 0) throw new IllegalStateException("报告所有本期栏目读取失败，请稍后重试");
        return new Report(text.toString(), partial, List.copyOf(sections), period.currentLabel() + " · 对比 " + period.previousLabel(), generated);
    }

    private Snapshot read(String key, Supplier<Snapshot> source) {
        try { return source.get(); }
        catch (RuntimeException e) { log.warn("周期报告 {} 栏目读取失败：{}", key, e.getClass().getSimpleName()); return null; }
    }

    private Snapshot snapshot(String owner, String key, LocalDate from, LocalDate to, ZoneId zone) {
        Map<String, BigDecimal> values = new TreeMap<>();
        if ("expense".equals(key)) {
            var entries = expenses.reportExpenses(owner, from, to);
            for (var entry : entries) values.merge(entry.currency(), entry.amount(), BigDecimal::add);
            return new Snapshot(values, values.isEmpty() ? "支出：无记录\n" : "按币种分别统计，不折算合计。\n", entries);
        }
        if ("study".equals(key)) {
            var stat = studies.getStatistics(owner, from, to, zone);
            values.put("学习时长（分钟）", BigDecimal.valueOf(stat.totalDurationSeconds()).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));
            values.put("学习记录（条）", BigDecimal.valueOf(stat.sessionCount()));
            return new Snapshot(values, "按任务时区统计周期内的学习记录。\n");
        }
        Set<String> occurrences = new HashSet<>();
        List<String> pending = new ArrayList<>();
        long total = 0, done = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (var event : daily.getDay(owner, date).events()) {
                if (!(event.details() instanceof TodoDetails todo)) continue;
                String cycle = switch (todo.recurrence() == null ? "" : todo.recurrence().toLowerCase(Locale.ROOT)) {
                    case "weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
                    case "monthly" -> date.withDayOfMonth(1).toString();
                    case "daily" -> date.toString();
                    default -> "once";
                };
                if (!occurrences.add(event.id() + ":" + cycle)) continue;
                total++;
                if (todo.completed()) done++;
                else if (pending.size() < 8) pending.add("• " + date + " · " + event.title());
            }
        }
        values.put("应办（项）", BigDecimal.valueOf(total));
        values.put("已完成（项）", BigDecimal.valueOf(done));
        if (total > 0) values.put("完成率", BigDecimal.valueOf(done * 100).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP));
        String detail = total == 0 ? "本期无待办，完成率不适用。\n" : pending.isEmpty() ? "本期待办已全部完成。\n" : "尚未完成\n" + String.join("\n", pending) + "\n";
        if (total - done > pending.size()) detail += "仅列出前 8 项，请在日历查看全部。\n";
        return new Snapshot(values, detail);
    }

    /** 日报资产查询独立降级，不影响同一报告中的其他栏目。 */
    private FinanceService.DailyAssetData readDailyAssetData(String owner, LocalDate date) {
        try { return finance.getDailyAssetData(owner, date); }
        catch (RuntimeException e) {
            log.warn("日报资产与流水读取失败：{}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** 汇总账户当前余额、当日真实收支和逐笔账单；旧日历支出明确标记为未关联账户。 */
    private String dailyAssetDetail(FinanceService.DailyAssetData data,
                                    List<ExpenseAnalyticsService.ExpenseEntry> expenseEntries) {
        StringBuilder detail = new StringBuilder("资产余额（按账户币种，收益计提至报告日期）：\n");
        if (data.accounts().isEmpty()) detail.append("未设置资产账户。\n");
        for (FinanceAccount account : data.accounts()) {
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;
            for (FinanceTransaction transaction : data.transactions()) {
                if (!account.id().equals(transaction.accountId())) continue;
                if ("expense".equals(transaction.transactionType())) expense = expense.add(transaction.amount());
                if ("income".equals(transaction.transactionType()) || "yield".equals(transaction.transactionType()))
                    income = income.add(transaction.amount());
            }
            detail.append("• ").append(account.name()).append("：余额 ").append(format(account.balance(), true))
                    .append(' ').append(account.currency()).append("；收入 ").append(format(income, true))
                    .append(' ').append(account.currency()).append("；支出 ").append(format(expense, true))
                    .append(' ').append(account.currency()).append('\n');
        }

        detail.append("当日账户账单：\n");
        List<FinanceTransaction> cashflows = data.transactions().stream()
                .filter(transaction -> Set.of("expense", "income", "yield").contains(transaction.transactionType()))
                .toList();
        if (cashflows.isEmpty()) detail.append("无账户收入或支出记录。\n");
        for (FinanceTransaction transaction : cashflows) {
            String type = "expense".equals(transaction.transactionType()) ? "支出"
                    : "yield".equals(transaction.transactionType()) ? "收益" : "收入";
            detail.append("• ").append(transaction.time()).append(" · ").append(transaction.accountName())
                    .append(type).append(' ').append(format(transaction.amount(), true)).append(' ')
                    .append(transaction.currency()).append(" · ").append(transaction.category());
            if (transaction.note() != null && !transaction.note().isBlank()) detail.append(" · ").append(transaction.note());
            detail.append('\n');
        }

        List<FinanceTransaction> otherChanges = data.transactions().stream()
                .filter(transaction -> !Set.of("expense", "income", "yield").contains(transaction.transactionType()))
                .toList();
        if (!otherChanges.isEmpty()) {
            detail.append("其他资产变动（不计入收入/支出）：\n");
            for (FinanceTransaction transaction : otherChanges) {
                detail.append("• ").append(transaction.time()).append(" · ").append(transaction.accountName())
                        .append(transactionTypeLabel(transaction.transactionType())).append(' ')
                        .append(format(transaction.amount(), true)).append(' ').append(transaction.currency())
                        .append(" · ").append(transaction.note()).append('\n');
            }
        }

        List<ExpenseAnalyticsService.ExpenseEntry> unassignedExpenses = expenseEntries.stream()
                .filter(entry -> "calendar".equals(entry.source())).toList();
        if (!unassignedExpenses.isEmpty()) {
            detail.append("旧日历支出（未关联资产账户）：\n");
            for (var entry : unassignedExpenses) {
                detail.append("• ").append(entry.time()).append(" · 支出 ").append(format(entry.amount(), true))
                        .append(' ').append(entry.currency()).append(" · ").append(entry.category());
                if (entry.note() != null && !entry.note().isBlank()) detail.append(" · ").append(entry.note());
                detail.append('\n');
            }
        }
        return detail.toString();
    }

    private String transactionTypeLabel(String type) {
        return switch (type) {
            case "transfer_out" -> "转出";
            case "transfer_in" -> "转入";
            case "adjustment_increase" -> "余额调增";
            case "adjustment_decrease" -> "余额调减";
            default -> "资产变动";
        };
    }

    private List<Metric> compare(String key, Snapshot current, Snapshot previous) {
        Set<String> names = new TreeSet<>();
        if (current != null) names.addAll(current.values().keySet());
        if (previous != null) names.addAll(previous.values().keySet());
        if ("expense".equals(key) && names.isEmpty()) names.add("无支出");
        List<Metric> metrics = new ArrayList<>();
        for (String name : names) {
            boolean rate = "完成率".equals(name);
            BigDecimal a = current == null ? null : current.values().getOrDefault(name, rate ? null : BigDecimal.ZERO);
            BigDecimal b = previous == null ? null : previous.values().getOrDefault(name, rate ? null : BigDecimal.ZERO);
            String change = "无法比较";
            if (a != null && b != null) {
                BigDecimal delta = a.subtract(b);
                change = delta.signum() == 0 ? "持平" : (delta.signum() > 0 ? "+" : "") + format(delta, "expense".equals(key)) + (rate ? " 个百分点" : "");
                if (!rate && delta.signum() != 0) change += b.signum() == 0 ? "（上期为 0）" : "（" + (delta.signum() > 0 ? "+" : "")
                        + delta.multiply(BigDecimal.valueOf(100)).divide(b.abs(), 1, RoundingMode.HALF_UP).toPlainString() + "%）";
            }
            metrics.add(new Metric(name, display(a, current, rate, key), display(b, previous, rate, key), change));
        }
        return List.copyOf(metrics);
    }
    private String display(BigDecimal value, Snapshot snapshot, boolean rate, String key) {
        if (value == null) return rate && snapshot != null ? "不适用" : "不可用";
        return format(value, "expense".equals(key)) + (rate ? "%" : "");
    }
    private String format(BigDecimal value, boolean money) {
        return money ? value.setScale(2, RoundingMode.HALF_UP).toPlainString() : value.stripTrailingZeros().toPlainString();
    }
}
