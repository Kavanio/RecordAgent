package butvan.agent.network.mobiletask.dto;

import butvan.agent.network.mobiletask.model.MobileTaskModels.MobileTask;
import butvan.agent.network.mobiletask.model.MobileTaskModels.MobileTaskDelivery;
import butvan.agent.network.mobiletask.model.MobileTaskModels.PageResult;

import java.time.Instant;
import java.util.List;

/** 手机端通知任务 HTTP 请求与响应 DTO。 */
public final class MobileTaskDtos {

    private MobileTaskDtos() {
    }

    /** 创建或更新手机通知任务请求。 */
    public record SaveMobileTaskRequest(
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

    /** 上报系统本地通知送达回执请求。 */
    public record RecordDeliveryRequest(String eventId, Instant deliveredAt) {
    }

    /** 手机通知任务响应。 */
    public record MobileTaskResponse(
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

    /** 通知送达观察回执响应。 */
    public record MobileTaskDeliveryResponse(String id, Instant deliveredAt) {
    }

    /** 分页响应。 */
    public record PageResponse<T>(List<T> items, Integer nextOffset, boolean hasMore) {
    }

    public static MobileTaskResponse from(MobileTask task) {
        return new MobileTaskResponse(
                task.id(), task.title(), task.body(), task.recurrence(),
                task.hour(), task.minute(), task.weekday(), task.dayOfMonth(),
                task.onceDate(), task.timeZone(), task.enabled(), task.version());
    }

    public static MobileTaskDeliveryResponse from(MobileTaskDelivery delivery) {
        return new MobileTaskDeliveryResponse(delivery.id(), delivery.deliveredAt());
    }

    public static PageResponse<MobileTaskResponse> fromTasks(PageResult<MobileTask> page) {
        return new PageResponse<>(page.items().stream().map(MobileTaskDtos::from).toList(),
                page.nextOffset(), page.hasMore());
    }

    public static PageResponse<MobileTaskDeliveryResponse> fromDeliveries(PageResult<MobileTaskDelivery> page) {
        return new PageResponse<>(page.items().stream().map(MobileTaskDtos::from).toList(),
                page.nextOffset(), page.hasMore());
    }
}
