package butvan.agent.network.automation;

import butvan.agent.network.automation.model.MailTemplate;
import butvan.agent.network.automation.model.TaskSpec;
import butvan.agent.network.automation.service.ReportPeriod;
import butvan.agent.network.automation.service.TaskSchedule;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证跨年、闰月及每月首日的完整周期边界。 */
class ReportPeriodTest {
    @Test void completedPeriodsRespectCalendarBoundaries() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        var planned = LocalDate.of(2024, 3, 1).atTime(8, 30).atZone(zone).toInstant();
        var month = ReportPeriod.completed("MONTHLY_REPORT", planned, zone);
        assertEquals(LocalDate.of(2024, 2, 1), month.from());
        assertEquals(LocalDate.of(2024, 2, 29), month.to());
        assertEquals(LocalDate.of(2024, 1, 1), month.previousFrom());
        assertEquals(LocalDate.of(2024, 1, 31), month.previousTo());
        var week = ReportPeriod.completed("WEEKLY_REPORT", Instant.parse("2026-01-05T01:00:00Z"), zone);
        assertEquals(LocalDate.of(2025, 12, 29), week.from());
        assertEquals(LocalDate.of(2026, 1, 4), week.to());
        assertEquals(LocalDate.of(2025, 12, 22), week.previousFrom());
    }
    @Test void monthlyScheduleOnlyRunsOnFirstDayAndLegacyStylesMap() {
        var spec = new TaskSpec("月报", "", "MONTHLY_REPORT", "MONTHLY", "Asia/Shanghai", "08:30", null,
                127, 45, 5, "00:00", "00:00", true, false, false, false, true, false, false);
        spec.validate();
        assertEquals(Instant.parse("2024-02-01T00:30:00Z").toEpochMilli(),
                TaskSchedule.next(spec, Instant.parse("2024-01-30T00:00:00Z")));
        assertEquals("DIGEST", new MailTemplate("CARDS", null, null, null, null, null).style());
        assertEquals("EDITORIAL", new MailTemplate("MINIMAL", null, null, null, null, null).style());
        assertEquals("LEDGER", new MailTemplate("LETTER", null, null, null, null, null).style());
    }
}
