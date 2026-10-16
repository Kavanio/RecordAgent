package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 账户领域的 AuthSession 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record AuthSessionEntity(
        UUID id,
        UUID userId,
        String refreshTokenHash,
        String deviceLabel,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        OffsetDateTime createdAt,
        OffsetDateTime lastUsedAt) {}
