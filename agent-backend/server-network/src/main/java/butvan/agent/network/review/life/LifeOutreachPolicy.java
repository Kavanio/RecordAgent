package butvan.agent.network.review.life;

import butvan.agent.network.review.life.LifeReviewModels.*;
import java.time.*;

/** 值得主动呈现的最低证据门槛；空白日报和重复静态资产不制造主动消息。 */
public final class LifeOutreachPolicy {
    private LifeOutreachPolicy() {}
    /** 只有成功生成且有正文的版本可以投递，失败资料仅留在桌面诊断。 */
    public static boolean isDeliverable(Content c) {
        return "READY".equals(c.state()) && c.draft() != null
                && (!c.draft().observations().isEmpty() || !c.draft().actions().isEmpty());
    }
    public static boolean hasFreshInformation(Content c) {
        if (!isDeliverable(c)) return false;
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
