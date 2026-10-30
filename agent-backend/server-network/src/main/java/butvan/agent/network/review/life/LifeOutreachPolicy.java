package butvan.agent.network.review.life;

import butvan.agent.network.review.life.LifeReviewModels.*;
import java.time.*;

/** 值得主动呈现的最低证据门槛；空白日报和重复静态资产不制造主动消息。 */
public final class LifeOutreachPolicy {
    private LifeOutreachPolicy() {}
    public static boolean hasFreshInformation(Content c) {
        boolean newText = c.materials().stream().anyMatch(m -> {
            if (m.text().isBlank()) return false;
            if (m.kind().equals("SUPPLEMENT")) return true;
            try {
                if (m.occurredAt().length() == 10) return LocalDate.parse(m.occurredAt()).equals(c.day());
                return ZonedDateTime.parse(m.occurredAt()).withZoneSameInstant(ZoneId.of(c.timezone())).toLocalDate().equals(c.day());
            } catch (DateTimeException unavailable) { return false; }
        });
        boolean newActivity = c.facts().stream().anyMatch(f -> !f.metricKey().startsWith("asset_")
                && !f.value().equals("0") && !f.value().isBlank());
        return newText || newActivity;
    }
}
