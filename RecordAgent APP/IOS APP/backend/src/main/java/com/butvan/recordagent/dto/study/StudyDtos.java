package com.butvan.recordagent.dto.study;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** 已完成学习时段的自然日投影。 */
public final class StudyDtos {
    private StudyDtos() {}

    public record Session(UUID id, String title, LocalDate date, LocalTime time, long durationMinutes) {}
}
