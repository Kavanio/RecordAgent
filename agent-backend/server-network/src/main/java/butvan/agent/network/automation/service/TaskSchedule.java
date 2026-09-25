package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.TaskSpec;
import java.time.*;

/** 纯时间计算，无调度线程，便于模拟时钟跳变和夏令时。 */
public final class TaskSchedule {
    private TaskSchedule() {}
    /** 返回严格晚于给定时刻的下次触发；使用状态触发不产生墙上时钟时间。 */
    public static Long next(TaskSpec spec, Instant after) {
        if ("ACTIVITY".equals(spec.trigger())) return null;
        if ("ONCE".equals(spec.trigger())) {
            var at = Instant.parse(spec.onceAt());
            return at.isAfter(after) && spec.activeAt(at) ? at.toEpochMilli() : null;
        }
        if ("INTERVAL".equals(spec.trigger())) {
            Instant candidate = after;
            // 最长跨一周寻找下一个生效间隔，预览不会显示免打扰时段内的触发。
            for (int i = 0; i <= 10080; i++) {
                candidate = candidate.plusSeconds(spec.minutes() * 60L);
                if (spec.activeAt(candidate)) return candidate.toEpochMilli();
            }
            throw new IllegalArgumentException("未来没有符合生效时段的间隔时间");
        }
        ZoneId zone = ZoneId.of(spec.timezone());
        LocalDate date = after.atZone(zone).toLocalDate();
        for (int i = 0; i < 370; i++) {
            LocalDateTime candidate = date.plusDays(i).atTime(LocalTime.parse(spec.atTime()));
            if ("MONTHLY".equals(spec.trigger()) && candidate.getDayOfMonth() != 1) continue;
            var offsets = zone.getRules().getValidOffsets(candidate);
            if (offsets.isEmpty()) continue; // 春季不存在的本地时间当天跳过。
            Instant instant = candidate.atOffset(offsets.getFirst()).toInstant();
            if (instant.isAfter(after) && spec.activeAt(instant)) return instant.toEpochMilli();
        }
        throw new IllegalArgumentException("未来一年内没有符合生效时段的执行时间");
    }
}
