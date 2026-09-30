package com.butvan.recordagent.dto.record;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** 资料列表只读取摘要，正文按稳定 ID 读取；手记沿用同一张资料表。 */
public final class LibraryDtos {
    private LibraryDtos() {}

    public record Item(
            UUID id,
            LocalDate date,
            String type,
            String title,
            String excerpt,
            long characterCount,
            boolean favorite,
            boolean trashed,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record Document(
            UUID id,
            LocalDate date,
            String type,
            String title,
            String body,
            boolean favorite,
            boolean trashed,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    public record Save(
            @NotNull UUID id,
            @NotNull LocalDate date,
            @NotBlank String type,
            @NotBlank @Size(max = 240) String title,
            @NotNull @Size(max = 100000) String body,
            @NotBlank String timeZone,
            @Min(0) long version) {}

    public record Flags(@Min(0) long version, boolean favorite, boolean trashed) {}
}
