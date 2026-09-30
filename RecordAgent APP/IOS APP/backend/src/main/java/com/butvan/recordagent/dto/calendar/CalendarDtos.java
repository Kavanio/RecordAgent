package com.butvan.recordagent.dto.calendar;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** 日历待办写入和有界日/月读取契约。 */
public final class CalendarDtos {
    private CalendarDtos() {}

    public record CreateTodo(
            @NotBlank @Size(max = 240) String title,
            @Size(max = 10000) String detail,
            @NotNull LocalDate date,
            LocalTime time,
            @NotBlank String timeZone,
            @NotBlank String recurrence,
            @Min(1) @Max(7) Integer recurrenceWeekday) {}

    /** 周便签读取整周真实发生日，月便签读取月末实例；每组受明细上限约束。 */
    public record PeriodicTodos(List<Todo> weekly, List<Todo> monthly) {}

    public record Created(UUID id, long version) {}

    public record CompleteOccurrence(
            @NotNull Boolean completed, @NotNull @PositiveOrZero Long expectedVersion) {}

    public record Occurrence(UUID id, LocalDate date, boolean completed, long version) {}

    public record Todo(
            UUID id,
            String title,
            String detail,
            LocalDate date,
            LocalTime time,
            String recurrence,
            boolean completed,
            long version) {}

    public record Day(LocalDate date, List<Todo> todos) {}

    public record MonthDay(LocalDate date, int count, List<String> titles) {}

    public record Month(String month, List<MonthDay> days) {}
}
