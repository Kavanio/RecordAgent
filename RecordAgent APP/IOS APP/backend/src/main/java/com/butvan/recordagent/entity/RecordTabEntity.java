package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 资料领域的 RecordTab 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record RecordTabEntity(
        UUID id,
        UUID ownerId,
        String name,
        String systemKey,
        Integer sortOrder,
        OffsetDateTime createdAt) {}
