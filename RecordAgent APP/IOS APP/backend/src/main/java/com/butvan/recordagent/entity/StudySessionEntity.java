package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 学习领域的 StudySession 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record StudySessionEntity(
        UUID id,
        UUID ownerId,
        String title,
        UUID categoryId,
        String location,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String timezone,
        String source,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
