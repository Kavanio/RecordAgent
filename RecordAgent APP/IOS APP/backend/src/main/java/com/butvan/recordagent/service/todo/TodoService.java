package com.butvan.recordagent.service.todo;

import com.butvan.recordagent.dto.todo.CalendarDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** 待办发生日、周期完成和版本冲突服务接口。 */
public interface TodoService {

    /** 创建待办。 */
    CalendarDtos.Created create(UUID owner, CalendarDtos.CreateTodo request);

    /** 读取当日待办。 */
    CalendarDtos.Day day(UUID owner, LocalDate date, String zone);

    /** 读取月历待办摘要。 */
    CalendarDtos.Month month(UUID owner, YearMonth month, String zone);

    /** 更新待办实例完成状态。 */
    CalendarDtos.Occurrence complete(UUID owner, UUID id, LocalDate date, CalendarDtos.CompleteOccurrence request);
}
