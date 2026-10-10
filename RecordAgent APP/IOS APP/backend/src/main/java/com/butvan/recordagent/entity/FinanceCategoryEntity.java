package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 财务领域的 FinanceCategory 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record FinanceCategoryEntity(
        UUID id,
        UUID ownerId,
        String transactionType,
        String name,
        String color,
        OffsetDateTime createdAt) {}
