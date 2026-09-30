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
    List<com.butvan.recordagent.service.query.DailySummary> month(UUID owner, YearMonth month);
    /** 个人总览使用账户余额与累计支出。 */
    Summary summary(UUID owner);
    /** 某自然日全部支出，独立于明细上限。 */
    long expenseOn(UUID owner, LocalDate date);

    /** 分页读取本人自然日明细，offset 从零开始，limit 最大为配置上限。 */
    com.butvan.recordagent.dto.query.Page<FinanceDtos.Transaction> page(UUID owner, LocalDate date, int offset, int limit);

    /** 分页读取本人账户；页大小遵守统一明细上限。 */
    com.butvan.recordagent.dto.query.Page<FinanceDtos.Account> accountsPage(UUID owner, int offset, int limit);

    /** 幂等收支写入；同用户同键同请求返回首次响应，换请求返回 409。null 仅兼容旧客户端。 */
    FinanceDtos.Transaction createTransaction(UUID owner, FinanceDtos.CreateTransaction request, UUID requestId);

}
