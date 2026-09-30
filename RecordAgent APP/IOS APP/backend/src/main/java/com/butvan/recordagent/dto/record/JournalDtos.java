package com.butvan.recordagent.dto.record;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 手记写入与资料日历投影契约；Journal 为所有资料类型共用的兼容展示结构。 */
public final class JournalDtos {
    private JournalDtos() {}

    public record Create(
            @NotNull LocalDate date,
            @NotBlank @Size(max = 240) String title,
            @Size(max = 100000) String body,
            @NotBlank String timeZone) {}

    public record Created(UUID id, long version) {}

    public record Journal(
            UUID id,
            LocalDate date,
            String title,
            String body,
            long version,
            Instant createdAt,
            Instant updatedAt) {}
}
