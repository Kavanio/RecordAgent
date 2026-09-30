package com.butvan.recordagent.entity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 财务领域的 FinanceTransaction 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record FinanceTransactionEntity(
        UUID id,
        UUID ownerId,
        UUID accountId,
        UUID groupId,
        LocalDate transactionDate,
        LocalTime transactionTime,
        String transactionType,
        UUID categoryId,
        String note,
        Long amountMinor,
        String currency,
        String source,
        OffsetDateTime createdAt) {}
