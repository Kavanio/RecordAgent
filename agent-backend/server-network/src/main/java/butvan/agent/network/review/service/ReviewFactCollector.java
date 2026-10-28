package butvan.agent.network.review.service;

import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.finance.service.FinanceService;
import butvan.agent.network.review.model.ReviewModels.*;
import butvan.agent.network.study.service.StudyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 经各领域只读 Service 采集有界历史事实，不解析邮件、不返回账户名称或订单备注。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewFactCollector {
    private final FinanceService finance;
    private final ExpenseAnalyticsService expenses;
    private final DailyEventService daily;
    private final StudyService study;

    public record Collected(List<Fact> facts, List<SourceStatus> sources) {
        public Collected { facts = List.copyOf(facts); sources = List.copyOf(sources); }
    }

    /** 单个来源失败时丢弃该来源的中间结果，不留下看似完整的半份金融统计。 */
    public Collected collect(String owner, LocalDate from, LocalDate to, ZoneId zone, Set<String> domains) {
        ReviewPolicy.requireOwner(owner);
        ReviewPolicy.scope(domains, ReviewPolicy.DOMAINS);
        if (from == null || to == null || zone == null || from.isAfter(to)
                || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= 7)
            throw new IllegalArgumentException("摘要采集日期无效，单次最多 7 日");
        List<Fact> facts = new ArrayList<>();
        List<SourceStatus> sources = new ArrayList<>();
        for (String domain : new TreeSet<>(domains)) {
            List<Fact> part = new ArrayList<>();
            try {
                boolean empty = switch (domain) {
                    case "FINANCE" -> finance(owner, from, to, zone, part);
                    case "TODO" -> todos(owner, from, to, part);
                    case "STUDY" -> study(owner, from, to, zone, part);
                    default -> throw new IllegalArgumentException("不支持的复盘数据范围");
                };
                facts.addAll(part);
                sources.add(new SourceStatus(domain, empty ? "EMPTY" : "AVAILABLE"));
            } catch (RuntimeException exception) {
                log.warn("复盘事实来源读取失败：domain={} type={}", domain, exception.getClass().getSimpleName());
                sources.add(new SourceStatus(domain, "UNAVAILABLE"));
            }
        }
        facts.sort(Comparator.comparing(Fact::domain).thenComparing(Fact::metricKey)
                .thenComparing(Fact::subjectId).thenComparing(Fact::currency));
        return new Collected(facts, sources);
    }

    private boolean finance(String owner, LocalDate from, LocalDate to, ZoneId zone, List<Fact> facts) {
        Map<String, BigDecimal> incomes = new TreeMap<>(), costs = new TreeMap<>();
        for (var item : finance.getReportTransactions(owner, from, to))
            if (Set.of("income", "yield").contains(item.transactionType()))
                incomes.merge(item.currency(), item.amount(), BigDecimal::add);
        for (var item : expenses.reportExpenses(owner, from, to)) costs.merge(item.currency(), item.amount(), BigDecimal::add);
        Set<String> currencies = new TreeSet<>(incomes.keySet());
        currencies.addAll(costs.keySet());
        for (String currency : currencies) {
            BigDecimal income = incomes.getOrDefault(currency, BigDecimal.ZERO), cost = costs.getOrDefault(currency, BigDecimal.ZERO);
            facts.add(money("income", "", currency, income));
            facts.add(money("expense", "", currency, cost));
            facts.add(money("net_cashflow", "", currency, income.subtract(cost)));
        }
        var assets = finance.getReportAssets(owner, to, zone);
        for (var asset : assets) facts.add(money("asset_balance_book_reconstruction", asset.id(), asset.currency(), asset.balance()));
        return currencies.isEmpty() && assets.isEmpty();
    }

    private boolean todos(String owner, LocalDate from, LocalDate to, List<Fact> facts) {
        Set<String> occurrences = new HashSet<>();
        long total = 0, completed = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (var todo : daily.getTodoOccurrences(owner, date)) {
                String cycle = switch (todo.recurrence() == null ? "" : todo.recurrence().toLowerCase(Locale.ROOT)) {
                    case "daily" -> date.toString();
                    case "weekly" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
                    case "monthly" -> date.withDayOfMonth(1).toString();
                    default -> "once";
                };
                if (!occurrences.add(todo.id() + ":" + cycle)) continue;
                total++;
                if (todo.completed()) completed++;
            }
        }
        facts.add(new Fact("TODO", "occurrences", "", "", "COUNT", total));
        facts.add(new Fact("TODO", "completed", "", "", "COUNT", completed));
        return total == 0;
    }

    private boolean study(String owner, LocalDate from, LocalDate to, ZoneId zone, List<Fact> facts) {
        var stat = study.getStatistics(owner, from, to, zone);
        facts.add(new Fact("STUDY", "duration", "", "", "SECONDS", stat.totalDurationSeconds()));
        facts.add(new Fact("STUDY", "sessions", "", "", "COUNT", stat.sessionCount()));
        facts.add(new Fact("STUDY", "study_days", "", "", "COUNT", stat.studyDays()));
        return stat.sessionCount() == 0 && stat.totalDurationSeconds() == 0;
    }

    private Fact money(String key, String subjectId, String currency, BigDecimal value) {
        return new Fact("FINANCE", key, subjectId, currency, "MONEY_MINOR", value.movePointRight(2).longValueExact());
    }
}
