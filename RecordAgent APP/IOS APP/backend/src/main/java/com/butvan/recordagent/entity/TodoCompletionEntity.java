package com.butvan.recordagent.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 日历领域的 TodoCompletion 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record TodoCompletionEntity(
        UUID entryId, LocalDate periodStart, OffsetDateTime completedAt) {}
