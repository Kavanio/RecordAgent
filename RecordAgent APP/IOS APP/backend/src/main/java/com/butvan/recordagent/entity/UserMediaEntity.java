package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 文件领域的 UserMedia 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record UserMediaEntity(
        UUID ownerId,
        UUID avatarFileId,
        UUID backgroundFileId,
        Long version,
        OffsetDateTime updatedAt) {}
