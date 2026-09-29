package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 资料领域的 RecordTag 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record RecordTagEntity(
        UUID id,
        UUID ownerId,
        String name,
        OffsetDateTime createdAt
) {
}
