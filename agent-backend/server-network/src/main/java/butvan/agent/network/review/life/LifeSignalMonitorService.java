package butvan.agent.network.review.life;

import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry;
import butvan.agent.network.dailycontext.dto.DailyContextDtos.SummaryResponse;
import butvan.agent.network.dailycontext.dto.DailyContextDtos.WeatherResponse;
import butvan.agent.network.dailycontext.service.DailyContextService;
import butvan.agent.network.review.life.LifeReviewModels.Habit;
import butvan.agent.network.review.life.LifeReviewModels.Settings;
import butvan.agent.network.review.life.LifeReviewModels.SignalState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * M6 现实信息源只读监测服务（第一批：天气显著变化、支出显著变化）。
 *
 * <p>规则判断、基线对比与恢复事件全部由确定性逻辑完成，不调用大模型，
 * 复用 {@link LifeDeliveryService#sendNudge} 实现幂等去重与本人飞书投递。</p>
 */
@Slf4j
@Service
public class LifeSignalMonitorService {
    private static final List<String> ADVERSE_WEATHER_KEYWORDS = List.of(
            "雨", "雪", "雷", "雹", "雾", "霾", "沙", "尘", "暴", "台风", "大风");
    private static final double ADVERSE_WIND_SPEED_MS = 10.8;
    private static final double SIGNIFICANT_TEMP_DELTA_C = 8.0;
    private static final int MIN_PRIOR_EXPENSE_DAYS = 3;
    private static final BigDecimal EXPENSE_SPIKE_MULTIPLIER = BigDecimal.valueOf(2);
    private static final BigDecimal MIN_EXPENSE_ALERT_AMOUNT = new BigDecimal("200.00");

    private final LifeReviewRepository repository;
    private final LifeDeliveryService deliveries;
    private final DailyContextService dailyContext;
    private final ExpenseAnalyticsService expenseAnalytics;
    private final ServerProbeClient probeClient;

    @FunctionalInterface
    public interface ServerProbeClient {
        ProbeResult probe(String url);
        record ProbeResult(boolean up, int statusCode, String message) {}
    }

    public LifeSignalMonitorService(
            LifeReviewRepository repository,
            LifeDeliveryService deliveries,
            DailyContextService dailyContext,
            ExpenseAnalyticsService expenseAnalytics) {
        this(repository, deliveries, dailyContext, expenseAnalytics, defaultProbeClient());
    }

    @Autowired
    public LifeSignalMonitorService(
            LifeReviewRepository repository,
            LifeDeliveryService deliveries,
            @Autowired(required = false) DailyContextService dailyContext,
            @Autowired(required = false) ExpenseAnalyticsService expenseAnalytics,
            @Autowired(required = false) ServerProbeClient probeClient) {
        this.repository = repository;
        this.deliveries = deliveries;
        this.dailyContext = dailyContext;
        this.expenseAnalytics = expenseAnalytics;
        this.probeClient = probeClient != null ? probeClient : defaultProbeClient();
    }

    /** 检查已开启的现实信息源变化；各信息源独立降级，互不阻断。 */
    public void checkSignals(Settings settings, ZonedDateTime local, Instant now) {
        if (settings == null || !settings.proactive() || !settings.feishu()) return;
        try {
            checkWeather(settings, local, now);
        } catch (RuntimeException e) {
            log.warn("天气变化监测降级跳过：{}", e.getClass().getSimpleName());
        }
        try {
            checkExpense(settings, local, now);
        } catch (RuntimeException e) {
            log.warn("支出变化监测降级跳过：{}", e.getClass().getSimpleName());
        }
        try {
            checkServer(settings, local, now);
        } catch (RuntimeException e) {
            log.warn("服务器状态监测降级跳过：{}", e.getClass().getSimpleName());
        }
    }

    private void checkWeather(Settings s, ZonedDateTime local, Instant now) {
        if (!s.weatherSignalEnabled() || dailyContext == null) return;
        int hour = local.getHour();
        if (hour < 8 || hour >= 22) return;

        LocalDate today = local.toLocalDate();
        SummaryResponse summary = dailyContext.getSummary(today, true, false);
        if (summary == null || summary.weather() == null) return;

        WeatherResponse w = summary.weather();
        String location = (w.locationName() == null || w.locationName().isBlank()) ? "当前地点" : w.locationName().strip();
        String condition = (w.condition() == null || w.condition().isBlank()) ? "未知" : w.condition().strip();
        boolean adverse = isAdverseWeather(condition, w.windSpeed());
        Optional<SignalState> prevOpt = repository.signalState(s.owner(), "WEATHER");

        if (prevOpt.isEmpty()) {
            if (adverse) {
                String eventKey = "weather:" + today + ":" + condition;
                repository.saveSignalState(new SignalState(
                        s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), eventKey, now));
                String text = "【天气变化提醒】" + location + "当前天气为" + condition
                        + "（" + formatTemp(w.temperature()) + "°C，体感 " + formatTemp(w.feelsLike()) + "°C），外出请留意天气变化。";
                deliveries.sendNudge(s.owner(), "WEATHER_ALERT", eventKey, text);
            } else {
                repository.saveSignalState(new SignalState(
                        s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now));
            }
            return;
        }

        SignalState prev = prevOpt.get();
        ParsedWeatherBaseline parsed = parseWeatherBaseline(prev.baselineValue());
        String prevActiveKey = prev.activeEventKey() == null ? "" : prev.activeEventKey();

        if (adverse) {
            String eventKey = "weather:" + today + ":" + condition;
            repository.saveSignalState(new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), eventKey, now));
            if (!eventKey.equals(prevActiveKey)) {
                String text = "【天气变化提醒】" + location + "当前转为" + condition
                        + "（" + formatTemp(w.temperature()) + "°C，体感 " + formatTemp(w.feelsLike()) + "°C），外出请留意天气变化。";
                deliveries.sendNudge(s.owner(), "WEATHER_ALERT", eventKey, text);
            }
            return;
        }

        if (!prevActiveKey.isBlank()) {
            String recoveryKey = "recovery:" + prevActiveKey;
            repository.saveSignalState(new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now));
            String text = "【天气恢复提醒】" + location + "天气已转为" + condition
                    + "（当前 " + formatTemp(w.temperature()) + "°C），先前提醒的天气状况已缓解。";
            deliveries.sendNudge(s.owner(), "WEATHER_RECOVERY", recoveryKey, text);
            return;
        }

        if (parsed.temperature() != null && Math.abs(w.temperature() - parsed.temperature()) >= SIGNIFICANT_TEMP_DELTA_C) {
            boolean up = w.temperature() > parsed.temperature();
            String dir = up ? "升温" : "降温";
            String deltaKey = "weather-delta:" + today + ":" + (up ? "up" : "down");
            double diff = Math.abs(w.temperature() - parsed.temperature());
            repository.saveSignalState(new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now));
            String text = "【天气变化提醒】" + location + "较前次观测明显" + dir + " " + formatTemp(diff)
                    + "°C（当前" + condition + " " + formatTemp(w.temperature()) + "°C，体感 "
                    + formatTemp(w.feelsLike()) + "°C），注意适时增减衣物。";
            deliveries.sendNudge(s.owner(), "WEATHER_ALERT", deltaKey, text);
            return;
        }

        if (!today.toString().equals(parsed.date())) {
            repository.saveSignalState(new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now));
        }
    }

    private void checkExpense(Settings s, ZonedDateTime local, Instant now) {
        if (!s.expenseSignalEnabled() || expenseAnalytics == null) return;
        int minHour = repository.habit(s.owner())
                .filter(h -> h.active() && now.isBefore(h.expiresAt()))
                .map(Habit::availableHour)
                .orElse(12);
        if (local.getHour() < minHour || !local.toLocalTime().isBefore(LocalTime.of(23, 30))) return;

        LocalDate today = local.toLocalDate();
        List<ExpenseEntry> entries = expenseAnalytics.reportExpenses(s.owner(), today.minusDays(28), today);
        if (entries == null || entries.isEmpty()) return;

        Map<String, List<ExpenseEntry>> byCurrency = new TreeMap<>();
        for (ExpenseEntry entry : entries) {
            if (entry.amount() == null || entry.amount().compareTo(BigDecimal.ZERO) <= 0) continue;
            String currency = (entry.currency() == null || entry.currency().isBlank()) ? "CNY" : entry.currency().strip();
            byCurrency.computeIfAbsent(currency, k -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<String, List<ExpenseEntry>> item : byCurrency.entrySet()) {
            String currency = item.getKey();
            List<ExpenseEntry> prior = new ArrayList<>();
            List<ExpenseEntry> todayEntries = new ArrayList<>();
            for (ExpenseEntry entry : item.getValue()) {
                if (today.equals(entry.date())) {
                    todayEntries.add(entry);
                } else if (entry.date() != null && entry.date().isBefore(today)) {
                    prior.add(entry);
                }
            }
            if (todayEntries.isEmpty() || prior.isEmpty()) continue;

            long activePriorDays = prior.stream().map(ExpenseEntry::date).distinct().count();
            if (activePriorDays < MIN_PRIOR_EXPENSE_DAYS) continue;

            BigDecimal priorTotal = prior.stream()
                    .map(ExpenseEntry::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal dailyAvg = priorTotal.divide(BigDecimal.valueOf(activePriorDays), 2, RoundingMode.HALF_UP);
            BigDecimal todayTotal = todayEntries.stream()
                    .map(ExpenseEntry::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal threshold = dailyAvg.multiply(EXPENSE_SPIKE_MULTIPLIER).max(MIN_EXPENSE_ALERT_AMOUNT);
            if (todayTotal.compareTo(threshold) < 0) continue;

            String topCategory = resolveTopCategory(todayEntries);
            String dedupKey = "expense:" + today + ":" + currency;
            String text = "【支出变化提醒】今日（" + today + "）已记录 " + currency + " 支出 "
                    + todayTotal.toPlainString() + "（主要分类：" + topCategory
                    + "），高于近 28 天活跃日均基线（" + dailyAvg.toPlainString() + "），顺手帮你留个记录参考。";
            repository.saveSignalState(new SignalState(
                    s.owner(), "EXPENSE", today + "|" + currency + "|" + todayTotal.toPlainString(), dedupKey, now));
            deliveries.sendNudge(s.owner(), "EXPENSE_ALERT", dedupKey, text);
        }
    }

    private void checkServer(Settings s, ZonedDateTime local, Instant now) {
        if (!s.serverSignalEnabled() || probeClient == null) return;
        Optional<SignalState> prevOpt = repository.signalState(s.owner(), "SERVER");
        if (prevOpt.isPresent()) {
            Instant lastObserved = prevOpt.get().observedAt();
            if (now.isBefore(lastObserved.plus(Duration.ofSeconds(30)))) {
                return;
            }
        }

        ServerProbeClient.ProbeResult result;
        try {
            result = probeClient.probe(s.serverProbeUrl());
        } catch (Exception e) {
            result = new ServerProbeClient.ProbeResult(false, 0, "探测请求失败：" + e.getClass().getSimpleName());
        }

        LocalDate today = local.toLocalDate();
        String name = s.serverProbeName();

        if (result.up()) {
            if (prevOpt.isPresent() && !prevOpt.get().activeEventKey().isBlank()) {
                String activeKey = prevOpt.get().activeEventKey();
                String recoveryKey = "recovery:" + activeKey;
                repository.saveSignalState(new SignalState(
                        s.owner(), "SERVER", "OK|" + result.statusCode(), "", now));
                String text = "【服务器恢复提醒】" + name + "健康检查已恢复正常（"
                        + result.message() + "），探针服务已重新就绪。";
                deliveries.sendNudge(s.owner(), "SERVER_RECOVERY", recoveryKey, text);
            } else {
                repository.saveSignalState(new SignalState(
                        s.owner(), "SERVER", "OK|" + result.statusCode(), "", now));
            }
            return;
        }

        String eventKey = "server:" + today + ":" + name;
        if (prevOpt.isEmpty() || prevOpt.get().activeEventKey().isBlank()) {
            repository.saveSignalState(new SignalState(
                    s.owner(), "SERVER", "FAIL|" + result.statusCode() + "|" + result.message(), eventKey, now));
            String text = "【服务器状态提醒】" + name + "健康检查检测到异常（"
                    + result.message() + "），请留意服务可用性。";
            deliveries.sendNudge(s.owner(), "SERVER_ALERT", eventKey, text);
        } else {
            repository.saveSignalState(new SignalState(
                    s.owner(), "SERVER", "FAIL|" + result.statusCode() + "|" + result.message(), prevOpt.get().activeEventKey(), now));
        }
    }

    private static ServerProbeClient defaultProbeClient() {
        var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        return url -> {
            try {
                var request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .timeout(Duration.ofSeconds(3))
                        .header("User-Agent", "ButvanAgent-ServerProbe/1.0")
                        .GET()
                        .build();
                var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
                int code = response.statusCode();
                boolean up = code >= 200 && code < 400;
                return new ServerProbeClient.ProbeResult(up, code, up ? "HTTP " + code : "HTTP " + code + " 状态异常");
            } catch (java.net.http.HttpConnectTimeoutException | java.net.ConnectException e) {
                return new ServerProbeClient.ProbeResult(false, 0, "连接失败或超时");
            } catch (java.net.http.HttpTimeoutException e) {
                return new ServerProbeClient.ProbeResult(false, 0, "响应读取超时");
            } catch (Exception e) {
                return new ServerProbeClient.ProbeResult(false, 0, "探测请求失败：" + e.getClass().getSimpleName());
            }
        };
    }

    private static String resolveTopCategory(List<ExpenseEntry> entries) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (ExpenseEntry entry : entries) {
            String cat = (entry.category() == null || entry.category().isBlank()) ? "日常支出" : entry.category().strip();
            sums.merge(cat, entry.amount(), BigDecimal::add);
        }
        return sums.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("日常支出");
    }

    private static boolean isAdverseWeather(String condition, double windSpeed) {
        if (windSpeed >= ADVERSE_WIND_SPEED_MS) return true;
        return ADVERSE_WEATHER_KEYWORDS.stream().anyMatch(condition::contains);
    }

    private static String formatWeatherBaseline(LocalDate date, String condition, double temperature) {
        return date + "|" + condition + "|" + formatTemp(temperature);
    }

    private static ParsedWeatherBaseline parseWeatherBaseline(String raw) {
        if (raw == null || raw.isBlank()) return new ParsedWeatherBaseline("", "", null);
        String[] parts = raw.split("\\|", 3);
        if (parts.length < 3) return new ParsedWeatherBaseline("", raw, null);
        Double temp = null;
        try {
            temp = Double.parseDouble(parts[2]);
        } catch (NumberFormatException ignored) {
        }
        return new ParsedWeatherBaseline(parts[0], parts[1], temp);
    }

    private static String formatTemp(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.05) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record ParsedWeatherBaseline(String date, String condition, Double temperature) {}
}
