package com.butvan.recordagent.entity;

import java.time.LocalTime;
import java.util.UUID;

/** 日历领域的 CalendarTodo 表行快照；仅用于持久化映射，不直接返回给 API。 */
public record CalendarTodoEntity(
        UUID entryId,
        String entryType,
        LocalTime todoTime,
        String priority,
        String recurrence,
        Short recurrenceWeekday,
        Short recurrenceMonthDay) {}
