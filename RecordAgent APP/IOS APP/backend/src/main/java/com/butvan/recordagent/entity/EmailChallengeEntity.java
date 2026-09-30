package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 账户领域的 EmailChallenge 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record EmailChallengeEntity(
        UUID id,
        String emailNormalized,
        String purpose,
        String codeHash,
        OffsetDateTime expiresAt,
        OffsetDateTime consumedAt,
        Integer attemptCount,
        OffsetDateTime createdAt) {}
