package com.butvan.recordagent.dto.task;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.UUID;

/** 手机自动提醒配置；注册系统通知与真实送达是不同状态。 */
public final class TaskDtos {
    private TaskDtos() {}

    public record Task(
            UUID id,
            String title,
            String body,
            String recurrence,
            int hour,
            int minute,
            Integer weekday,
            Integer dayOfMonth,
            LocalDate onceDate,
            String timeZone,
            boolean enabled,
            long version) {}

    public record Save(
            @NotNull UUID id,
            @NotBlank @Size(max = 120) String title,
            @NotNull @Size(max = 500) String body,
            @NotBlank String recurrence,
            @Min(0) @Max(23) int hour,
            @Min(0) @Max(59) int minute,
            Integer weekday,
            Integer dayOfMonth,
            LocalDate onceDate,
            @NotBlank String timeZone,
            boolean enabled,
            @Min(0) long version) {}

    public record Observation(
            @NotBlank @Size(max = 160) String eventId, @NotNull OffsetDateTime deliveredAt) {}

    public record Delivery(String id, OffsetDateTime deliveredAt) {}
}
