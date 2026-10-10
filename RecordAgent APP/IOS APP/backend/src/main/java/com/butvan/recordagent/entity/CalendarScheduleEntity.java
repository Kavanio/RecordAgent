package com.butvan.recordagent.entity;

import java.time.LocalTime;
import java.util.UUID;

/** 日历领域的 CalendarSchedule 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record CalendarScheduleEntity(
        UUID entryId,
        String entryType,
        LocalTime startTime,
        LocalTime endTime,
        String timezone,
        String location,
        String recurrenceRule) {}
