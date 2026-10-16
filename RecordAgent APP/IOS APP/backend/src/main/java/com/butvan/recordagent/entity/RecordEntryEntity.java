package com.butvan.recordagent.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 资料领域的 RecordEntry 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record RecordEntryEntity(
        UUID id,
        UUID ownerId,
        UUID tabId,
        LocalDate recordDate,
        String recordType,
        String title,
        String contentHtml,
        String contentText,
        Integer weekYear,
        Short weekNumber,
        Boolean pinned,
        Boolean favorite,
        Boolean archived,
        OffsetDateTime trashedAt,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
