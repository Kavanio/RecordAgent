package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.TaskSpec;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.study.service.StudyService;
import butvan.agent.network.finance.model.FinanceModels.FinanceAccount;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.study.model.StudyModels.StudySession;
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
    private static final int TODO_DETAIL_LIMIT = 30;
    private final DailyEventService daily;
    private final ExpenseAnalyticsService expenses;
    private final StudyService studies;
    private final FinanceService finance;

    public record Metric(String label, String current, String previous, String change) {}
    public record Section(String key, String title, String text, List<Metric> metrics, String detail) {
        public Section(String key, String title, String text) { this(key, title, text, List.of(), text); }
    }
    /** 报告当天单个资产账户的余额与收支汇总。 */
    public record AccountSummary(String name, String currency, String balance, String income, String expense) {}
    /** 日报轨迹中的待办完成、学习、收支或其他资产变动。 */
    public record TimelineItem(String time, String type, String title, String detail, String amount) {}
    public record Report(String text, boolean partial, List<Section> sections, String date, String generatedAt,
                         List<AccountSummary> accounts, List<TimelineItem> timeline) {
        public Report {
            sections = sections == null ? List.of() : List.copyOf(sections);
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
            timeline = timeline == null ? List.of() : List.copyOf(timeline);
        }
        public Report(String text, boolean partial, List<Section> sections, String date, String generatedAt) {
            this(text, partial, sections, date, generatedAt, List.of(), List.of());
        }
        public Report(String text, boolean partial) { this(text, partial, List.of(), "", "", List.of(), List.of()); }
    }
    private record Snapshot(Map<String, BigDecimal> values, String detail,
                            List<ExpenseAnalyticsService.ExpenseEntry> expenseEntries,
                            List<TimelineItem> timeline) {
        private Snapshot(Map<String, BigDecimal> values, String detail) { this(values, detail, List.of(), List.of()); }
        private Snapshot(Map<String, BigDecimal> values, String detail,
                         List<ExpenseAnalyticsService.ExpenseEntry> expenseEntries) {
            this(values, detail, expenseEntries, List.of());
        }
    }

    /** 计划时刻决定本期；预览和手动执行由调用方传入当前时刻。 */
    public Report build(String owner, TaskSpec spec, Instant planned, Instant now) {
        if (!spec.isReport()) return new Report(spec.content(), false);
        ZoneId zone = ZoneId.of(spec.timezone());
        ReportPeriod period = ReportPeriod.completed(spec.kind(), planned, zone);
        String generated = now.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "（" + spec.timezone() + "）";
        List<Section> sections = new ArrayList<>();
        List<AccountSummary> accounts = new ArrayList<>();
        List<TimelineItem> timeline = new ArrayList<>();
        StringBuilder text = new StringBuilder(spec.reportLabel() + " · " + period.currentLabel() + "\n对比上期：" + period.previousLabel() + "\n生成于 " + generated + "\n");
        Map<String, Snapshot> currentSources = new HashMap<>(), previousSources = new HashMap<>();
        int available = 0;
        boolean partial = false;
        for (String key : List.of("expense", "income", "todo", "study")) {
            if (!(switch (key) { case "expense", "income" -> spec.expense(); case "todo" -> spec.todo(); default -> spec.study(); })) continue;
            Snapshot current = read(key, () -> snapshot(owner, key, period.from(), period.to(), zone));
            Snapshot previous = read(key, () -> snapshot(owner, key, period.previousFrom(), period.previousTo(), zone));
            currentSources.put(key, current);
            previousSources.put(key, previous);
            if (current != null) available++;
            partial |= current == null || previous == null;
            String title = switch (key) {
                case "expense" -> "DAILY_REPORT".equals(spec.kind()) ? "资产与收支" : "支出概览";
                case "income" -> "收入概览";
                case "todo" -> "待办进度";
                default -> "学习时光";
            };
            if ("todo".equals(key) && "DAILY_REPORT".equals(spec.kind()) && current != null)
                timeline.addAll(current.timeline());
            List<Metric> metrics = new ArrayList<>(compare(key, current, previous));
            if ("MONTHLY_REPORT".equals(spec.kind()) && Set.of("expense", "income", "study").contains(key)) {
                metrics.addAll(compare(key, averageSnapshot(current, period.from(), period.to()),
                        averageSnapshot(previous, period.previousFrom(), period.previousTo())).stream()
                        .filter(metric -> metric.label().endsWith(" · 日均")).toList());
            }
            String currentDetail = current == null ? "本期数据暂不可用。\n" : current.detail();
            if ("expense".equals(key) && "DAILY_REPORT".equals(spec.kind()) && current != null) {
                FinanceService.DailyAssetData assetData = readDailyAssetData(owner, period.from());
                if (assetData == null) {
                    currentDetail += "资产余额与账户流水暂不可用。\n";
                    partial = true;
                } else {
                    currentDetail += dailyAssetDetail(assetData, current.expenseEntries());
                    accounts.addAll(accountSummaries(assetData));
                    timeline.addAll(financeTimeline(assetData));
                }
                if (current != null) timeline.addAll(unassignedExpenseTimeline(current.expenseEntries()));
            }
            if ("study".equals(key) && "DAILY_REPORT".equals(spec.kind()) && current != null) {
                try {
                    timeline.addAll(studyTimeline(owner, period.from(), zone, now));
                } catch (RuntimeException e) {
                    log.warn("日报学习轨迹读取失败：{}", e.getClass().getSimpleName());
                    partial = true;
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
        if (spec.expense()) {
            Snapshot currentNet = netSnapshot(currentSources.get("income"), currentSources.get("expense"));
            Snapshot previousNet = netSnapshot(previousSources.get("income"), previousSources.get("expense"));
            List<Metric> metrics = compare("income", currentNet, previousNet);
            String detail = "收支结余 = 已记录收入（含已入账收益）− 已记录支出；不含划账、初始资金与余额校准，不代表资产增减。\n"
                    + (currentNet == null ? "本期收支结余暂不可用。\n" : "")
                    + (previousNet == null ? "上期收支结余暂不可用。\n" : "");
            StringBuilder summary = new StringBuilder(detail);
            metrics.forEach(metric -> summary.append(metric.label()).append("：本期 ").append(metric.current())
                    .append(" · 上期 ").append(metric.previous()).append(" · ").append(metric.change()).append('\n'));
            sections.add(new Section("net", "收支结余", summary.toString(), metrics, detail));
            text.append('\n').append("收支结余\n").append(summary);
        }
        if (spec.expense() && !"DAILY_REPORT".equals(spec.kind())) {
            Section assets = FinancialReportSections.assets(finance, owner, period.to(), period.previousTo(), zone);
            sections.add(assets);
            partial |= assets.detail().contains("暂不可用");
            text.append('\n').append(assets.title()).append('\n').append(assets.text());
        }
        if (available == 0) throw new IllegalStateException("报告所有本期栏目读取失败，请稍后重试");
        timeline.sort(Comparator.comparing(item -> item.time() == null || item.time().isBlank() ? "99:99" : item.time()));
        return new Report(text.toString(), partial, List.copyOf(sections), period.currentLabel() + " · 对比 " + period.previousLabel(), generated,
                accounts, timeline);
    }

    /** 月报同时提供日均指标，消除自然月天数不同造成的总量误读。 */
    private Snapshot averageSnapshot(Snapshot source, LocalDate from, LocalDate to) {
        if (source == null) return null;
        BigDecimal days = BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
        Map<String, BigDecimal> values = new TreeMap<>();
        source.values().forEach((name, amount) -> values.put(name + " · 日均",
                amount.divide(days, 2, RoundingMode.HALF_UP)));
        return new Snapshot(values, "");
    }

    /** 任何一侧不可用都不计算结余，避免缺失收入被当成零。 */
    private Snapshot netSnapshot(Snapshot income, Snapshot expense) {
        if (income == null || expense == null) return null;
        Map<String, BigDecimal> values = new TreeMap<>(income.values());
        expense.values().forEach((currency, amount) -> values.merge(currency, amount.negate(), BigDecimal::add));
        return new Snapshot(values, "");
    }

    /** 将账户余额和当日现金流转为邮件账户表格所需的安全展示值。 */
    private List<AccountSummary> accountSummaries(FinanceService.DailyAssetData data) {
        List<AccountSummary> summaries = new ArrayList<>();
        for (FinanceAccount account : data.accounts()) {
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expense = BigDecimal.ZERO;
            for (FinanceTransaction transaction : data.transactions()) {
                if (!account.id().equals(transaction.accountId())) continue;
                if ("expense".equals(transaction.transactionType())) expense = expense.add(transaction.amount());
                if ("income".equals(transaction.transactionType()) || "yield".equals(transaction.transactionType()))
                    income = income.add(transaction.amount());
            }
            summaries.add(new AccountSummary(account.name(), account.currency(), format(account.balance(), true),
                    format(income, true), format(expense, true)));
        }
        return List.copyOf(summaries);
    }

    /** 生成日报财务流水轨迹，保留旧日历支出与其他资产变动的来源语义。 */
    private List<TimelineItem> financeTimeline(FinanceService.DailyAssetData data) {
        List<TimelineItem> items = new ArrayList<>();
        for (FinanceTransaction transaction : data.transactions()) {
            String type = transaction.transactionType();
            String label = transaction.category() == null || transaction.category().isBlank() ? "收支记录" : transaction.category();
            String note = transaction.note() == null || transaction.note().isBlank() ? "" : " · " + transaction.note();
            String amount = format(transaction.amount(), true) + " " + transaction.currency();
            String timelineType;
            if ("expense".equals(type)) { timelineType = "expense"; amount = "− " + currencySymbol(transaction.currency()) + format(transaction.amount(), true); }
            else if ("income".equals(type) || "yield".equals(type)) { timelineType = "income"; amount = "+ " + currencySymbol(transaction.currency()) + format(transaction.amount(), true); }
            else {
                timelineType = "other";
                if ("transfer_out".equals(type) || "adjustment_decrease".equals(type)) amount = "− " + currencySymbol(transaction.currency()) + format(transaction.amount(), true);
                else amount = "+ " + currencySymbol(transaction.currency()) + format(transaction.amount(), true);
                label = transactionTypeLabel(type);
            }
            items.add(new TimelineItem(transaction.time() == null ? "" : transaction.time().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), timelineType,
                    label + note, transaction.accountName(), amount));
        }
        return items;
    }

    /** 未关联账户的旧日历支出同样进入日报轨迹，但不并入账户余额表。 */
    private List<TimelineItem> unassignedExpenseTimeline(List<ExpenseAnalyticsService.ExpenseEntry> entries) {
        return entries.stream().filter(entry -> "calendar".equals(entry.source())).map(entry -> {
            String title = (entry.category() == null || entry.category().isBlank() ? "支出" : entry.category())
                    + (entry.note() == null || entry.note().isBlank() ? "" : " · " + entry.note());
            return new TimelineItem(entry.time() == null ? "" : entry.time().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), "expense", title,
                    "未关联资产账户", "− " + currencySymbol(entry.currency()) + format(entry.amount(), true));
        }).toList();
    }

    /** 查询日报当天实际学习时段，并按该日边界裁剪跨日记录。 */
    private List<TimelineItem> studyTimeline(String owner, LocalDate date, ZoneId zone, Instant now) {
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<TimelineItem> items = new ArrayList<>();
        for (StudySession session : studies.getSessions(owner, date, date, zone)) {
            Instant end = session.endedAt() == null || session.endedAt().isAfter(now) ? now : session.endedAt();
            Instant start = session.startedAt().isAfter(dayStart) ? session.startedAt() : dayStart;
            if (end.isAfter(dayEnd)) end = dayEnd;
            if (!start.isBefore(end)) continue;
            long seconds = Duration.between(start, end).getSeconds();
            String startTime = start.atZone(zone).toLocalTime().toString().substring(0, 5);
            String endTime = end.atZone(zone).toLocalTime().toString().substring(0, 5);
            String duration = seconds / 3600 > 0 ? (seconds / 3600) + "h " + (seconds % 3600 / 60) + "m" : (seconds / 60) + "m";
            String category = session.category() == null || session.category().isBlank() ? "学习" : session.category();
            String detail = category + (session.location() == null || session.location().isBlank() ? "" : " · " + session.location());
            items.add(new TimelineItem(startTime, "study", session.content(), detail,
                    startTime + " — " + endTime + " · " + duration));
        }
        return items;
    }

    private String currencySymbol(String currency) {
        return "CNY".equalsIgnoreCase(currency) ? "¥" : currency + " ";
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
            return new Snapshot(values, (values.isEmpty() ? "支出：无记录\n" : "按币种分别统计，不折算合计。\n")
                    + FinancialReportSections.expenseDetails(entries, from.equals(to) ? 3 : 5), entries);
        }
        if ("income".equals(key)) {
            var transactions = finance.getReportTransactions(owner, from, to).stream()
                    .filter(item -> Set.of("income", "yield").contains(item.transactionType())).toList();
            for (var item : transactions) values.merge(item.currency(), item.amount(), BigDecimal::add);
            return new Snapshot(values, transactions.isEmpty() ? "收入：无记录\n"
                    : FinancialReportSections.incomeDetails(transactions, from.equals(to) ? 3 : 5));
        }
        if ("study".equals(key)) {
            var stat = studies.getStatistics(owner, from, to, zone);
            values.put("学习时长（分钟）", BigDecimal.valueOf(stat.totalDurationSeconds()).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP));
            values.put("学习记录（条）", BigDecimal.valueOf(stat.sessionCount()));
            return new Snapshot(values, "按任务时区统计周期内的学习记录。\n");
        }
        Set<String> occurrences = new HashSet<>();
        int detailLimit = from.equals(to) ? TODO_DETAIL_LIMIT : 3;
        List<String> completed = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<TimelineItem> completedTimeline = new ArrayList<>();
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
                if (todo.completed()) {
                    done++;
                    String completedTime = todo.completedAt() == null ? "" : todo.completedAt().atZone(zone)
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
                    if (completed.size() < detailLimit) completed.add("• " + date
                            + (completedTime.isBlank() ? "" : " " + completedTime) + " · " + event.title());
                    if (from.equals(to) && !completedTime.isBlank()) {
                        completedTimeline.add(new TimelineItem(completedTime, "todo", event.title(), "待办已勾选完成", ""));
                    }
                } else if (pending.size() < detailLimit) {
                    pending.add("• " + date + " · " + event.title());
                }
            }
        }
        values.put("应办（项）", BigDecimal.valueOf(total));
        values.put("已完成（项）", BigDecimal.valueOf(done));
        if (total > 0) values.put("完成率", BigDecimal.valueOf(done * 100).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP));
        StringBuilder detail = new StringBuilder();
        if (total == 0) {
            detail.append("本期无待办，完成率不适用。\n");
        } else {
            if (!completed.isEmpty()) detail.append("已完成\n").append(String.join("\n", completed)).append('\n');
            if (!pending.isEmpty()) detail.append("尚未完成\n").append(String.join("\n", pending)).append('\n');
            if (done > completed.size()) detail.append("已完成事项较多，仅列出前 ").append(detailLimit).append(" 项（按日期顺序），其余请在日历查看。\n");
            if (total - done > pending.size()) detail.append("未完成事项较多，仅列出前 ").append(detailLimit).append(" 项（按日期顺序），其余请在日历查看。\n");
        }
        return new Snapshot(values, detail.toString(), List.of(), completedTimeline);
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
        if (("expense".equals(key) || "income".equals(key)) && names.isEmpty()) names.add("无记录");
        List<Metric> metrics = new ArrayList<>();
        for (String name : names) {
            boolean rate = "完成率".equals(name);
            BigDecimal a = current == null ? null : current.values().getOrDefault(name, rate ? null : BigDecimal.ZERO);
            BigDecimal b = previous == null ? null : previous.values().getOrDefault(name, rate ? null : BigDecimal.ZERO);
            String change = "无法比较";
            if (a != null && b != null) {
                BigDecimal delta = a.subtract(b);
                change = delta.signum() == 0 ? "持平" : (delta.signum() > 0 ? "+" : "") + format(delta, "expense".equals(key) || "income".equals(key)) + (rate ? " 个百分点" : "");
                if (!rate && delta.signum() != 0) change += b.signum() == 0 ? "（上期为 0）" : "（" + (delta.signum() > 0 ? "+" : "")
                        + delta.multiply(BigDecimal.valueOf(100)).divide(b.abs(), 1, RoundingMode.HALF_UP).toPlainString() + "%）";
            }
            metrics.add(new Metric(name, display(a, current, rate, key), display(b, previous, rate, key), change));
        }
        return List.copyOf(metrics);
    }
    private String display(BigDecimal value, Snapshot snapshot, boolean rate, String key) {
        if (value == null) return rate && snapshot != null ? "不适用" : "不可用";
        return format(value, "expense".equals(key) || "income".equals(key)) + (rate ? "%" : "");
    }
    private String format(BigDecimal value, boolean money) {
        return money ? value.setScale(2, RoundingMode.HALF_UP).toPlainString() : value.stripTrailingZeros().toPlainString();
    }
}
