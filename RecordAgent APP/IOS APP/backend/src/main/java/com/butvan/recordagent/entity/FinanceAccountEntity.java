package com.butvan.recordagent.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 财务领域的 FinanceAccount 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record FinanceAccountEntity(
        UUID id,
        UUID ownerId,
        String name,
        String accountType,
        String currency,
        Long balanceMinor,
        Boolean interestEnabled,
        BigDecimal annualRatePercent,
        LocalDate lastAccrualDate,
        Long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
