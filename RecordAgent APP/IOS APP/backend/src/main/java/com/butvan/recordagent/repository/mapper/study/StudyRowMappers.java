package com.butvan.recordagent.repository.mapper.study;

import com.butvan.recordagent.entity.StudyCategoryEntity;
import com.butvan.recordagent.entity.StudySessionEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;

/** 学习领域表到持久化实体的 JDBC 行映射；查询须使用表的完整列或同名别名。 */
public final class StudyRowMappers {
    public static final RowMapper<StudyCategoryEntity> STUDY_CATEGORY =
            DataClassRowMapper.newInstance(StudyCategoryEntity.class);

    public static final RowMapper<StudySessionEntity> STUDY_SESSION =
            DataClassRowMapper.newInstance(StudySessionEntity.class);

    private StudyRowMappers() {
    }
}
