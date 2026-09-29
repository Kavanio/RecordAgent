package com.butvan.recordagent.repository.mapper.file;

import com.butvan.recordagent.entity.FileAssetEntity;
import com.butvan.recordagent.entity.RecordFileEntity;
import com.butvan.recordagent.entity.CalendarFileEntity;
import com.butvan.recordagent.entity.UserMediaEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;

/** 文件领域表到持久化实体的 JDBC 行映射；查询须使用表的完整列或同名别名。 */
public final class FileRowMappers {
    public static final RowMapper<FileAssetEntity> FILE_ASSET =
            DataClassRowMapper.newInstance(FileAssetEntity.class);

    public static final RowMapper<RecordFileEntity> RECORD_FILE =
            DataClassRowMapper.newInstance(RecordFileEntity.class);

    public static final RowMapper<CalendarFileEntity> CALENDAR_FILE =
            DataClassRowMapper.newInstance(CalendarFileEntity.class);

    public static final RowMapper<UserMediaEntity> USER_MEDIA =
            DataClassRowMapper.newInstance(UserMediaEntity.class);

    private FileRowMappers() {
    }
}
