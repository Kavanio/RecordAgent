package com.butvan.recordagent.repository.mapper.auth;

import com.butvan.recordagent.entity.AppUserEntity;
import com.butvan.recordagent.entity.AuthSessionEntity;
import com.butvan.recordagent.entity.EmailChallengeEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;

/** 账户领域表到持久化实体的 JDBC 行映射；查询须使用表的完整列或同名别名。 */
public final class AccountRowMappers {
    public static final RowMapper<AppUserEntity> APP_USER =
            DataClassRowMapper.newInstance(AppUserEntity.class);

    public static final RowMapper<EmailChallengeEntity> EMAIL_CHALLENGE =
            DataClassRowMapper.newInstance(EmailChallengeEntity.class);

    public static final RowMapper<AuthSessionEntity> AUTH_SESSION =
            DataClassRowMapper.newInstance(AuthSessionEntity.class);

    private AccountRowMappers() {}
}
