package com.butvan.recordagent.dto.study;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** 已完成学习时段的自然日投影。 */
public final class StudyDtos {
    private StudyDtos() {}

    public record Session(
            UUID id, String title, LocalDate date, LocalTime time, long durationMinutes) {}

    public record Entry(
            UUID id,
            String title,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime endedAt,
            String timeZone,
            long version) {}

    public record Save(
            @jakarta.validation.constraints.NotNull UUID id,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 240)
                    String title,
            @jakarta.validation.constraints.NotNull java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime endedAt,
            @jakarta.validation.constraints.NotBlank String timeZone) {}

    public record Finish(
            @jakarta.validation.constraints.Min(0) long version,
            @jakarta.validation.constraints.NotNull java.time.OffsetDateTime endedAt) {}

    public record Overview(long minutes, long sessionCount, long activeDays) {}
}
