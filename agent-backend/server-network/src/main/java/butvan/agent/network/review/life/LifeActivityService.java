package butvan.agent.network.review.life;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.SessionCatalogService;
import butvan.agent.agents.session.TranscriptService;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.TranscriptMessageDto;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.review.life.LifeReviewDtos.HabitSuggestionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 仅基于交互时间戳观察作息建议与最近互动时间；不读取消息正文、不调用模型、不静默改写作息候选。
 */
@Service
@RequiredArgsConstructor
public class LifeActivityService {
    private static final int WINDOW_DAYS = 14;
    private static final int MIN_ACTIVE_DAYS = 3;
    private static final int MIN_WINDOW_SAMPLES = 5;

    private final SessionCatalogService sessions;
    private final TranscriptService transcripts;
    private final CurrentUserProvider user;
    private final RecordService records;
    private final LifeReviewRepository repository;

    /**
     * 聚合最近一次用户主动互动时间（普通聊天用户消息、复盘补充与反馈、近期手记更新）。
     */
    public Optional<Instant> lastInteractionAt(String owner, ZoneId zone, Instant now) {
        List<Instant> candidates = new ArrayList<>();
        repository.latestUserActivity(owner)
                .filter(t -> !t.isAfter(now))
                .ifPresent(candidates::add);
        try {
            LocalDate today = now.atZone(zone).toLocalDate();
            for (var item : records.journalFragments(owner, today.minusDays(6), today)) {
                if (item.updatedAt() != null && !item.updatedAt().isAfter(now)) {
                    candidates.add(item.updatedAt());
                }
            }
        } catch (RuntimeException ignored) {
            // 手记服务异常时不阻断时间戳聚合
        }
        for (Instant ts : userChatTimestamps(owner, now.minus(Duration.ofDays(30)), now)) {
            candidates.add(ts);
        }
        return candidates.stream().max(Comparator.naturalOrder());
    }

    /**
     * 统计近 14 天用户消息的时间戳分布，返回可解释的活跃时段建议（12—23 点）。
     */
    public HabitSuggestionView suggestHabit(String owner, String timezone, Instant now) {
        ZoneId zone = ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone);
        Instant from = now.minus(Duration.ofDays(WINDOW_DAYS));
        int[] counts = new int[24];
        Set<LocalDate> activeDates = new HashSet<>();
        int sampleCount = 0;

        for (Instant ts : userChatTimestamps(owner, from, now)) {
            var zdt = ts.atZone(zone);
            counts[zdt.getHour()]++;
            activeDates.add(zdt.toLocalDate());
            sampleCount++;
        }

        List<Integer> hourCounts = new ArrayList<>(24);
        int windowSamples = 0;
        for (int h = 0; h < 24; h++) {
            hourCounts.add(counts[h]);
            if (h >= 12) {
                windowSamples += counts[h];
            }
        }

        int activeDays = activeDates.size();
        if (activeDays < MIN_ACTIVE_DAYS || windowSamples < MIN_WINDOW_SAMPLES) {
            String note = "近 " + WINDOW_DAYS + " 天统计到 " + activeDays + " 个活跃日、共 " + sampleCount
                    + " 次互动时间戳（需至少 " + MIN_ACTIVE_DAYS + " 个活跃日且 12—23 点不少于 "
                    + MIN_WINDOW_SAMPLES + " 次互动才生成作息建议）。";
            return new HabitSuggestionView(false, null, sampleCount, activeDays, WINDOW_DAYS,
                    List.copyOf(hourCounts), note);
        }

        int bestHour = 12;
        for (int h = 13; h <= 23; h++) {
            if (counts[h] > counts[bestHour] || (counts[h] == counts[bestHour] && preferEveningTieBreak(h, bestHour))) {
                bestHour = h;
            }
        }
        String note = "基于近 " + WINDOW_DAYS + " 天 " + activeDays + " 个活跃日、共 " + sampleCount
                + " 次互动时间戳统计（未读取聊天正文），你在 " + bestHour
                + ":00 前后较为活跃，可一键采用为每日复盘与提醒时间。";
        return new HabitSuggestionView(true, bestHour, sampleCount, activeDays, WINDOW_DAYS,
                List.copyOf(hourCounts), note);
    }

    private boolean preferEveningTieBreak(int candidate, int current) {
        boolean candidateEvening = candidate >= 18 && candidate <= 22;
        boolean currentEvening = current >= 18 && current <= 22;
        if (candidateEvening != currentEvening) {
            return candidateEvening;
        }
        return candidate > current;
    }

    private List<Instant> userChatTimestamps(String owner, Instant from, Instant to) {
        if (owner == null || !owner.equals(user.currentUserId())) {
            return List.of();
        }
        List<Instant> timestamps = new ArrayList<>();
        try {
            List<SessionSummaryDto> generalSessions = sessions.listActive().stream()
                    .filter(s -> s.kind() == SessionKind.GENERAL)
                    .sorted(Comparator.comparing(SessionSummaryDto::updatedAt).reversed())
                    .limit(10)
                    .toList();
            for (var session : generalSessions) {
                for (var msg : transcripts.recent(session.id(), 48)) {
                    if (msg.role() != TranscriptMessageDto.MessageRole.USER || msg.createdAt() == null) {
                        continue;
                    }
                    if (!msg.createdAt().isBefore(from) && !msg.createdAt().isAfter(to)) {
                        timestamps.add(msg.createdAt());
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 会话目录不可读时返回已收集的时间戳，不抛错阻断
        }
        return timestamps;
    }
}
