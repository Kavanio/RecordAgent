package com.butvan.recordagent.repository.mapper.finance;

import com.butvan.recordagent.entity.FinanceAccountEntity;
import com.butvan.recordagent.entity.FinanceCategoryEntity;
import com.butvan.recordagent.entity.FinanceTransactionEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;

/** 财务领域表到持久化实体的 JDBC 行映射；查询须使用表的完整列或同名别名。 */
public final class FinanceRowMappers {
    public static final RowMapper<FinanceAccountEntity> FINANCE_ACCOUNT =
            DataClassRowMapper.newInstance(FinanceAccountEntity.class);

    public static final RowMapper<FinanceCategoryEntity> FINANCE_CATEGORY =
            DataClassRowMapper.newInstance(FinanceCategoryEntity.class);

    public static final RowMapper<FinanceTransactionEntity> FINANCE_TRANSACTION =
            DataClassRowMapper.newInstance(FinanceTransactionEntity.class);

    private FinanceRowMappers() {
    }
}
