package butvan.agent.network.review.life;

import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService.ExpenseEntry;
import butvan.agent.network.dailycontext.dto.DailyContextDtos.SummaryResponse;
import butvan.agent.network.dailycontext.dto.DailyContextDtos.WeatherResponse;
import butvan.agent.network.dailycontext.service.DailyContextService;
import butvan.agent.network.review.life.LifeReviewModels.Habit;
import butvan.agent.network.review.life.LifeReviewModels.Settings;
import butvan.agent.network.review.life.LifeReviewModels.SignalState;
import jakarta.mail.internet.InternetAddress;
import butvan.agent.network.review.service.ReviewPolicy;
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
import java.util.UUID;
import java.util.TreeMap;

/**
 * M6 现实信息源只读监测：天气、支出、服务器探针和重要邮件。
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
    private final MailInboxClient mailClient;

    @FunctionalInterface
    public interface ServerProbeClient {
        ProbeResult probe(String url);
        record ProbeResult(boolean up, int statusCode, String message) {}
    }

    @FunctionalInterface
    public interface MailInboxClient {
        InboxPage fetchUnreadMessages(String host, int port, String username, String password, int offset);
        record InboxPage(List<InboxMessageSummary> messages, int nextOffset) {}
        record InboxMessageSummary(String messageId, String from, String subject, Instant receivedAt) {}
    }

    public LifeSignalMonitorService(
            LifeReviewRepository repository,
            LifeDeliveryService deliveries,
            DailyContextService dailyContext,
            ExpenseAnalyticsService expenseAnalytics) {
        this(repository, deliveries, dailyContext, expenseAnalytics, defaultProbeClient(), defaultMailClient());
    }

    @Autowired
    public LifeSignalMonitorService(
            LifeReviewRepository repository,
            LifeDeliveryService deliveries,
            @Autowired(required = false) DailyContextService dailyContext,
            @Autowired(required = false) ExpenseAnalyticsService expenseAnalytics,
            @Autowired(required = false) ServerProbeClient probeClient,
            @Autowired(required = false) MailInboxClient mailClient) {
        this.repository = repository;
        this.deliveries = deliveries;
        this.dailyContext = dailyContext;
        this.expenseAnalytics = expenseAnalytics;
        this.probeClient = probeClient != null ? probeClient : defaultProbeClient();
        this.mailClient = mailClient != null ? mailClient : defaultMailClient();
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
        try {
            checkMail(settings, local, now);
        } catch (RuntimeException e) {
            log.warn("重要邮件监测降级跳过：{}", e.getClass().getSimpleName());
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
        String source = ReviewPolicy.fingerprint(location);
        Optional<SignalState> prevOpt = repository.signalState(s.owner(), "WEATHER")
                .filter(previous -> source.equals(previous.sourceFingerprint()));

        if (prevOpt.isEmpty()) {
            if (adverse) {
                String eventKey = "weather:" + UUID.randomUUID();
                repository.saveSignalState(s, new SignalState(
                        s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), eventKey, now, source));
                String text = "【天气变化提醒】" + location + "当前天气为" + condition
                        + "（" + formatTemp(w.temperature()) + "°C，体感 " + formatTemp(w.feelsLike()) + "°C），外出请留意天气变化。";
                deliveries.sendNudge(s, "WEATHER_ALERT", eventKey, text);
            } else {
                repository.saveSignalState(s, new SignalState(
                        s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now, source));
            }
            return;
        }

        SignalState prev = prevOpt.get();
        ParsedWeatherBaseline parsed = parseWeatherBaseline(prev.baselineValue());
        String prevActiveKey = prev.activeEventKey() == null ? "" : prev.activeEventKey();

        if (adverse) {
            String eventKey = !prevActiveKey.isBlank() && condition.equals(parsed.condition())
                    ? prevActiveKey : "weather:" + UUID.randomUUID();
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), eventKey, now, source));
            if (!eventKey.equals(prevActiveKey)) {
                String text = "【天气变化提醒】" + location + "当前转为" + condition
                        + "（" + formatTemp(w.temperature()) + "°C，体感 " + formatTemp(w.feelsLike()) + "°C），外出请留意天气变化。";
                deliveries.sendNudge(s, "WEATHER_ALERT", eventKey, text);
            }
            return;
        }

        if (!prevActiveKey.isBlank()) {
            String recoveryKey = "recovery:" + prevActiveKey;
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now, source));
            String text = "【天气恢复提醒】" + location + "天气已转为" + condition
                    + "（当前 " + formatTemp(w.temperature()) + "°C），先前提醒的天气状况已缓解。";
            deliveries.sendNudge(s, "WEATHER_RECOVERY", recoveryKey, text);
            return;
        }

        if (parsed.temperature() != null && Math.abs(w.temperature() - parsed.temperature()) >= SIGNIFICANT_TEMP_DELTA_C) {
            boolean up = w.temperature() > parsed.temperature();
            String dir = up ? "升温" : "降温";
            String deltaKey = "weather-delta:" + source + ":" + today + ":" + (up ? "up" : "down");
            double diff = Math.abs(w.temperature() - parsed.temperature());
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now, source));
            String text = "【天气变化提醒】" + location + "较前次观测明显" + dir + " " + formatTemp(diff)
                    + "°C（当前" + condition + " " + formatTemp(w.temperature()) + "°C，体感 "
                    + formatTemp(w.feelsLike()) + "°C），注意适时增减衣物。";
            deliveries.sendNudge(s, "WEATHER_ALERT", deltaKey, text);
            return;
        }

        if (!today.toString().equals(parsed.date())) {
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "WEATHER", formatWeatherBaseline(today, condition, w.temperature()), "", now, source));
        }
    }

    private void checkExpense(Settings s, ZonedDateTime local, Instant now) {
        if (!s.expenseSignalEnabled() || expenseAnalytics == null) return;
        String source = "expense";
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
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "EXPENSE", today + "|" + currency + "|" + todayTotal.toPlainString(), dedupKey, now, source));
            deliveries.sendNudge(s, "EXPENSE_ALERT", dedupKey, text);
        }
    }

    private void checkServer(Settings s, ZonedDateTime local, Instant now) {
        if (!s.serverSignalEnabled() || probeClient == null) return;
        String source = ReviewPolicy.fingerprint(s.serverProbeUrl() + "\n" + s.serverProbeName());
        Optional<SignalState> prevOpt = repository.signalState(s.owner(), "SERVER")
                .filter(previous -> source.equals(previous.sourceFingerprint()));
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

        String name = s.serverProbeName();

        if (result.up()) {
            if (prevOpt.isPresent() && !prevOpt.get().activeEventKey().isBlank()) {
                String activeKey = prevOpt.get().activeEventKey();
                String recoveryKey = "recovery:" + activeKey;
                repository.saveSignalState(s, new SignalState(
                        s.owner(), "SERVER", "OK|" + result.statusCode(), "", now, source));
                String text = "【服务器恢复提醒】" + name + "健康检查已恢复正常（"
                        + result.message() + "），探针服务已重新就绪。";
                deliveries.sendNudge(s, "SERVER_RECOVERY", recoveryKey, text);
            } else {
                repository.saveSignalState(s, new SignalState(
                        s.owner(), "SERVER", "OK|" + result.statusCode(), "", now, source));
            }
            return;
        }

        String eventKey = "server:" + UUID.randomUUID();
        if (prevOpt.isEmpty() || prevOpt.get().activeEventKey().isBlank()) {
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "SERVER", "FAIL|" + result.statusCode() + "|" + result.message(), eventKey, now, source));
            String text = "【服务器状态提醒】" + name + "健康检查检测到异常（"
                    + result.message() + "），请留意服务可用性。";
            deliveries.sendNudge(s, "SERVER_ALERT", eventKey, text);
        } else {
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "SERVER", "FAIL|" + result.statusCode() + "|" + result.message(), prevOpt.get().activeEventKey(), now, source));
        }
    }

    private void checkMail(Settings s, ZonedDateTime local, Instant now) {
        if (!s.mailSignalEnabled() || mailClient == null) return;
        String source = ReviewPolicy.fingerprint(LifeMailCredentials.identity(s));
        Optional<SignalState> prevOpt = repository.signalState(s.owner(), "MAIL")
                .filter(previous -> source.equals(previous.sourceFingerprint()));
        if (prevOpt.isPresent()) {
            Instant lastObserved = prevOpt.get().observedAt();
            if (now.isBefore(lastObserved.plus(Duration.ofSeconds(60)))) {
                return;
            }
        }

        int offset = prevOpt.map(previous -> mailOffset(previous.baselineValue())).orElse(0);
        MailInboxClient.InboxPage page;
        try {
            page = mailClient.fetchUnreadMessages(s.imapHost(), s.imapPort(), s.imapUsername(), s.imapPassword(), offset);
        } catch (Exception e) {
            log.warn("获取 IMAP 未读邮件失败: {}", e.getClass().getSimpleName());
            repository.saveSignalState(s, new SignalState(
                    s.owner(), "MAIL", "FAIL|" + offset, "", now, source));
            return;
        }

        List<MailInboxClient.InboxMessageSummary> messages = page == null ? List.of() : page.messages();
        if (messages.isEmpty()) {
            repository.saveSignalState(s, new SignalState(s.owner(), "MAIL", "OK|0|EMPTY", "", now, source));
            return;
        }

        int triggeredCount = 0;
        for (MailInboxClient.InboxMessageSummary msg : messages) {
            if (!isMailMatching(msg.from(), msg.subject(), s.mailSenderWhitelist(), s.mailSubjectKeywords())) {
                continue;
            }
            String rawId = (msg.messageId() != null && !msg.messageId().isBlank())
                    ? msg.messageId()
                    : (msg.from() + ":" + msg.subject() + ":" + (msg.receivedAt() != null ? msg.receivedAt().toEpochMilli() : 0));
            String dedupKey = "mail:" + ReviewPolicy.fingerprint(source + "\n" + rawId);

            String sender = cleanSender(msg.from());
            String subject = (msg.subject() == null || msg.subject().isBlank()) ? "(无主题)" : msg.subject().strip();
            if (subject.length() > 60) {
                subject = subject.substring(0, 57) + "...";
            }
            String text = "【重要邮件提醒】检测到来自「" + sender + "」的重要邮件：《" + subject + "》，请留意查收。";

            // 升级前的旧编号仅在冻结文案也相同时沿用，避免升级后重发已处理邮件。
            var legacy = repository.findNudge(s.owner(), "MAIL_ALERT", "mail:" + Integer.toHexString(rawId.hashCode()));
            if (legacy.filter(n -> n.text().equals(text) && !n.state().equals("CANCELLED")).isPresent()) continue;

            deliveries.sendNudge(s, "MAIL_ALERT", dedupKey, text);
            triggeredCount++;
        }

        repository.saveSignalState(s, new SignalState(
                s.owner(), "MAIL", "OK|" + page.nextOffset() + "|COUNT=" + messages.size() + "|ALERTED=" + triggeredCount, "", now, source));
    }

    private static boolean isMailMatching(String from, String subject, String whitelistStr, String keywordsStr) {
        boolean hasWhitelist = whitelistStr != null && !whitelistStr.isBlank();
        boolean hasKeywords = keywordsStr != null && !keywordsStr.isBlank();
        if (!hasWhitelist && !hasKeywords) {
            return true;
        }

        String lowerFrom = "";
        try {
            var addresses = InternetAddress.parse(from == null ? "" : from, true);
            if (addresses.length == 1) lowerFrom = addresses[0].getAddress().toLowerCase(Locale.ROOT);
        } catch (jakarta.mail.internet.AddressException invalid) {
            log.debug("邮件发件人地址格式无效，按非白名单处理");
        }
        String lowerSubject = subject == null ? "" : subject.toLowerCase(Locale.ROOT);

        if (hasWhitelist) {
            String[] senders = whitelistStr.split("[,，]");
            for (String s : senders) {
                String trimmed = s.strip().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && lowerFrom.equals(trimmed)) {
                    return true;
                }
            }
        }

        if (hasKeywords) {
            String[] keywords = keywordsStr.split("[,，]");
            for (String kw : keywords) {
                String trimmed = kw.strip().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && lowerSubject.contains(trimmed)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String cleanSender(String from) {
        if (from == null || from.isBlank()) return "未知发件人";
        String trimmed = from.strip();
        int angleStart = trimmed.indexOf('<');
        if (angleStart > 0) {
            String name = trimmed.substring(0, angleStart).strip();
            name = name.replaceAll("^[\"']+|[\"']+$", "").strip();
            if (!name.isBlank()) return name;
        }
        return trimmed;
    }

    /** 生产客户端与测试共用同一有界、只读网络适配器。 */
    static MailInboxClient defaultMailClient() { return LifeSignalClients.mail(); }
    static ServerProbeClient defaultProbeClient() { return LifeSignalClients.probe(); }

    private static int mailOffset(String baseline) {
        try {
            String[] parts = baseline.split("\\|", 3);
            return parts.length > 1 ? Math.max(0, Integer.parseInt(parts[1])) : 0;
        } catch (NumberFormatException invalid) { return 0; }
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
