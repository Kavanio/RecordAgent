package com.butvan.recordagent.repository.mapper;

import com.butvan.recordagent.entity.CalendarEntryEntity;
import com.butvan.recordagent.entity.CalendarTodoEntity;
import com.butvan.recordagent.entity.TodoCompletionEntity;
import com.butvan.recordagent.entity.CalendarScheduleEntity;
import com.butvan.recordagent.entity.CalendarPreferenceEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;

/** 日历领域表到持久化实体的 JDBC 行映射；查询须使用表的完整列或同名别名。 */
public final class CalendarRowMappers {
    public static final RowMapper<CalendarEntryEntity> CALENDAR_ENTRY =
            DataClassRowMapper.newInstance(CalendarEntryEntity.class);

    public static final RowMapper<CalendarTodoEntity> CALENDAR_TODO =
            DataClassRowMapper.newInstance(CalendarTodoEntity.class);

    public static final RowMapper<TodoCompletionEntity> TODO_COMPLETION =
            DataClassRowMapper.newInstance(TodoCompletionEntity.class);

    public static final RowMapper<CalendarScheduleEntity> CALENDAR_SCHEDULE =
            DataClassRowMapper.newInstance(CalendarScheduleEntity.class);

    public static final RowMapper<CalendarPreferenceEntity> CALENDAR_PREFERENCE =
            DataClassRowMapper.newInstance(CalendarPreferenceEntity.class);

    private CalendarRowMappers() {
    }
}
