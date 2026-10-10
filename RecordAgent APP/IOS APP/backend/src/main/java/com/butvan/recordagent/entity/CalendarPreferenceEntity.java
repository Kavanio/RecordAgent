package com.butvan.recordagent.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 日历领域的 CalendarPreference 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record CalendarPreferenceEntity(
        UUID ownerId,
        Boolean weeklyNoteVisible,
        Boolean monthlyNoteVisible,
        Boolean weeklyNoteExpanded,
        Boolean monthlyNoteExpanded,
        Long version,
        OffsetDateTime updatedAt) {}
