package butvan.agent.network.mobiletask.model;

import java.time.Instant;
import java.util.List;

/** 手机端本地通知提醒领域模型。 */
public final class MobileTaskModels {

    private MobileTaskModels() {
    }

    /** 一条手机端本地通知提醒配置。 */
    public record MobileTask(
            String id,
            String title,
            String body,
            String recurrence,
            int hour,
            int minute,
            Integer weekday,
            Integer dayOfMonth,
            String onceDate,
            String timeZone,
            boolean enabled,
            long version) {
    }

    /** 客户端上报的系统通知送达观察回执。 */
    public record MobileTaskDelivery(String id, Instant deliveredAt) {
    }

    /** 统一分页结构。 */
    public record PageResult<T>(List<T> items, Integer nextOffset, boolean hasMore) {
    }
}
