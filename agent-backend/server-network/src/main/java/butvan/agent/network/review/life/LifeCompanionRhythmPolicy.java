package butvan.agent.network.review.life;

import butvan.agent.network.review.life.LifeCompanionModels.Cognition;
import butvan.agent.network.review.life.LifeCompanionModels.RhythmState;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 主动陪伴动态节律与安静时段门控策略。
 *
 * <p>对齐设计规范：默认安静时段 {@code 22:00—09:00}、单自然日自动主动触达上限 {@code 1} 次、
 * 未回复时遇忙碌时段自动顺延，避免重复催促与夜间打扰。</p>
 */
public final class LifeCompanionRhythmPolicy {
    public static final int QUIET_START_HOUR = 22;
    public static final int QUIET_END_HOUR = 9;
    public static final Duration BASE_REACH_INTERVAL = Duration.ofHours(6);
    public static final Duration BACKOFF_REACH_INTERVAL = Duration.ofHours(12);

    private LifeCompanionRhythmPolicy() {
    }

    public static boolean isQuietHours(ZonedDateTime local) {
        int hour = local.getHour();
        return hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR;
    }

    public static boolean hasReachedDailyOutreachCap(RhythmState rhythm, ZonedDateTime local) {
        if (rhythm == null || rhythm.lastOutreachAt() == null) return false;
        LocalDate lastDay = rhythm.lastOutreachAt().atZone(local.getZone()).toLocalDate();
        return lastDay.equals(local.toLocalDate());
    }

    public static boolean isWithinBusyHours(int hour, int start, int end) {
        if (start <= end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    public static Instant nextAllowedMorning(ZonedDateTime local) {
        LocalDate day = local.getHour() >= QUIET_START_HOUR ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        return day.atTime(QUIET_END_HOUR, 30).atZone(local.getZone()).toInstant();
    }

    public static Instant deferAfterBusyHours(ZonedDateTime local, int busyEndHour) {
        int targetHour = Math.min(21, Math.max(QUIET_END_HOUR, busyEndHour));
        LocalDate targetDate = local.getHour() >= targetHour ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        ZonedDateTime candidate = targetDate.atTime(targetHour, 15).atZone(local.getZone());
        if (isQuietHours(candidate)) {
            return nextAllowedMorning(candidate);
        }
        return candidate.toInstant();
    }

    public static Instant computeNextReachAt(Instant now, ZoneId zone, Cognition cog, int unansweredCount) {
        Duration wait = unansweredCount <= 0
                ? BASE_REACH_INTERVAL
                : BACKOFF_REACH_INTERVAL.multipliedBy(Math.min(3, unansweredCount));
        ZonedDateTime candidate = now.plus(wait).atZone(zone);
        if (isQuietHours(candidate)) {
            candidate = nextAllowedMorning(candidate).atZone(zone);
        }
        if (unansweredCount > 0 && isWithinBusyHours(candidate.getHour(), cog.busyStartHour(), cog.busyEndHour())) {
            return deferAfterBusyHours(candidate, cog.busyEndHour());
        }
        return candidate.toInstant();
    }
}
