package com.butvan.recordagent.service.finance;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** 账户余额与流水的事务性业务边界。 */
public interface FinanceService {
    record Summary(long totalAssetsInCents, long totalExpenseInCents) {}
    /** 创建账户及期初余额。 */
    FinanceDtos.Account createAccount(UUID owner, FinanceDtos.CreateAccount request);
    /** 读取当前用户账户。 */
    List<FinanceDtos.Account> accounts(UUID owner);
    /** 记录一笔收支并更新账户余额。 */
    FinanceDtos.Transaction createTransaction(UUID owner, FinanceDtos.CreateTransaction request);
    /** 查询某日流水。 */
    List<FinanceDtos.Transaction> day(UUID owner, LocalDate date);
    /** 查询某月流水摘要。 */
    List<FinanceDtos.Transaction> month(UUID owner, YearMonth month);
    /** 个人总览使用账户余额与累计支出。 */
    Summary summary(UUID owner);
}
