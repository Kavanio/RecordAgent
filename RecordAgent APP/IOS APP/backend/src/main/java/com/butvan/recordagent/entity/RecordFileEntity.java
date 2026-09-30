package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 文件领域的 RecordFile 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record RecordFileEntity(
        UUID ownerId, UUID recordId, UUID fileId, String role, OffsetDateTime createdAt) {}
