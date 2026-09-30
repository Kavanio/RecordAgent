package com.butvan.recordagent.service.finance;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** 账户余额与流水的事务性业务边界。 */
public interface FinanceService {
    record Summary(long totalAssetsInCents, long totalExpenseInCents) {}

    /** 为当前身份创建账户；金额为整数分，期初余额不生成收入流水，同名冲突返回 409。 */
    FinanceDtos.Account createAccount(UUID owner, FinanceDtos.CreateAccount request);

    /** 读取本人完整账户列表；超过配置上限返回 422，可改用 accountsPage。 */
    List<FinanceDtos.Account> accounts(UUID owner);

    /** 兼容旧调用方的无幂等写入；流水与余额同事务，余额不足或溢出返回 400。新客户端使用带键重载。 */
    FinanceDtos.Transaction createTransaction(UUID owner, FinanceDtos.CreateTransaction request);

    /** 按写入自然日读取本人流水，超限返回 422；列表余额为账户当前余额。 */
    List<FinanceDtos.Transaction> day(UUID owner, LocalDate date);

    /** 返回全月按日期和收支类型聚合的完整计数，每组最多三条分类标题，不读取备注。 */
    List<DailySummary> month(UUID owner, YearMonth month);

    /** 个人总览使用账户余额与累计支出。 */
    Summary summary(UUID owner);

    /** 某自然日全部支出，独立于明细上限。 */
    long expenseOn(UUID owner, LocalDate date);

    /** 分页读取本人自然日明细，offset 从零开始，limit 最大为配置上限。 */
    Page<FinanceDtos.Transaction> page(UUID owner, LocalDate date, int offset, int limit);

    /** 分页读取本人账户；页大小遵守统一明细上限。 */
    Page<FinanceDtos.Account> accountsPage(UUID owner, int offset, int limit);

    /** 幂等收支写入；同用户同键同请求返回首次响应，换请求返回 409。null 仅兼容旧客户端。 */
    FinanceDtos.Transaction createTransaction(
            UUID owner, FinanceDtos.CreateTransaction request, UUID requestId);
}
