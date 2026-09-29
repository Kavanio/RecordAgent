package com.butvan.recordagent.repository.mapper.record;

import com.butvan.recordagent.entity.RecordTabEntity;
import com.butvan.recordagent.entity.RecordEntryEntity;
import com.butvan.recordagent.entity.RecordTagEntity;
import com.butvan.recordagent.entity.RecordEntryTagEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;

/** 资料领域表到持久化实体的 JDBC 行映射；查询须使用表的完整列或同名别名。 */
public final class RecordRowMappers {
    public static final RowMapper<RecordTabEntity> RECORD_TAB =
            DataClassRowMapper.newInstance(RecordTabEntity.class);

    public static final RowMapper<RecordEntryEntity> RECORD_ENTRY =
            DataClassRowMapper.newInstance(RecordEntryEntity.class);

    public static final RowMapper<RecordTagEntity> RECORD_TAG =
            DataClassRowMapper.newInstance(RecordTagEntity.class);

    public static final RowMapper<RecordEntryTagEntity> RECORD_ENTRY_TAG =
            DataClassRowMapper.newInstance(RecordEntryTagEntity.class);

    private RecordRowMappers() {
    }
}
