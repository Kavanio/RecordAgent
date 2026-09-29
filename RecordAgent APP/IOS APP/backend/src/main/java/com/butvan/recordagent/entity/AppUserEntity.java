package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 账户领域的 AppUser 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record AppUserEntity(
        UUID id,
        String emailNormalized,
        String passwordHash,
        String displayName,
        String displayNameNormalized,
        String status,
        String locale,
        String timezone,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
