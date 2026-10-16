package butvan.agent.network.automation.service;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

/** 以任务时区计算报告本期和对比周期。 */
public record ReportPeriod(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {
    /** 日报统计计划执行日当天，周报和月报统计最近已结束周期。 */
    public static ReportPeriod completed(String kind, Instant planned, ZoneId zone) {
        LocalDate today = planned.atZone(zone).toLocalDate();
        return switch (kind) {
            case "DAILY_REPORT" -> new ReportPeriod(today, today, today.minusDays(1), today.minusDays(1));
            case "WEEKLY_REPORT" -> {
                LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
                yield new ReportPeriod(start, start.plusDays(6), start.minusWeeks(1), start.minusDays(1));
            }
            case "MONTHLY_REPORT" -> {
                LocalDate start = today.withDayOfMonth(1).minusMonths(1);
                yield new ReportPeriod(start, start.plusMonths(1).minusDays(1), start.minusMonths(1), start.minusDays(1));
            }
            default -> throw new IllegalArgumentException("报告周期不支持");
        };
    }
    /** 本期在报告中展示的日期范围。 */
    public String currentLabel() { return label(from, to); }
    /** 对比周期在报告中展示的日期范围。 */
    public String previousLabel() { return label(previousFrom, previousTo); }
    private static String label(LocalDate from, LocalDate to) {
        return from.equals(to) ? from.toString() : from + " — " + to;
    }
}
