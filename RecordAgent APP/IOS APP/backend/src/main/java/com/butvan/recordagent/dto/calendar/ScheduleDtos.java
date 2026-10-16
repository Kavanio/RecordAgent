package com.butvan.recordagent.dto.calendar;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 日程创建与日历投影的数据契约。 */
public final class ScheduleDtos {
    private ScheduleDtos() {}

    public record Create(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 10000) String detail,
            @NotNull OffsetDateTime startAt,
            OffsetDateTime endAt,
            @NotBlank String timeZone) {}

    public record Created(UUID id, long version) {}

    public record Schedule(
            UUID id,
            String title,
            String detail,
            LocalDate date,
            LocalTime time,
            LocalTime endTime,
            String timeZone,
            long version) {}
}
