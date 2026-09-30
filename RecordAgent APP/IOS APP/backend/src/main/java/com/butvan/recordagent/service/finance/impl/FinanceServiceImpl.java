package com.butvan.recordagent.service.finance.impl;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.butvan.recordagent.dto.query.DailySummary;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.finance.FinanceRepository;
import com.butvan.recordagent.service.finance.FinanceRequestCodec;
import com.butvan.recordagent.service.finance.FinanceService;
import com.butvan.recordagent.service.query.ReadBounds;
import com.butvan.recordagent.service.time.BusinessTimeZone;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保证一笔收支与账户余额在同一事务内提交。 */
@Service
@RequiredArgsConstructor
public class FinanceServiceImpl implements FinanceService {
    private final FinanceRepository store;
    private final FinanceRequestCodec codec;
    private final ReadBounds bounds;

    @Override
    @Transactional
    public FinanceDtos.Account createAccount(UUID owner, FinanceDtos.CreateAccount request) {
        UUID id = UUID.randomUUID();
        String name = request.name().strip();
        try {
            store.createAccount(id, owner, name, request.openingBalanceInCents());
        } catch (DuplicateKeyException exception) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "account name already exists");
        }
        return new FinanceDtos.Account(id, name, request.openingBalanceInCents(), 0);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceDtos.Account> accounts(UUID owner) {
        return bounds.requireComplete(
                store.accounts(owner, bounds.accountLimit() + 1), bounds.accountLimit());
    }

    @Override
    @Transactional
    public FinanceDtos.Transaction createTransaction(
            UUID owner, FinanceDtos.CreateTransaction request) {
        return createTransaction(owner, request, null);
    }

    /** 同一用户同一幂等键先串行化，再查询提交结果，最后才修改余额。 */
    @Override
    @Transactional
    public FinanceDtos.Transaction createTransaction(
            UUID owner, FinanceDtos.CreateTransaction request, UUID requestId) {
        String hash = requestId == null ? null : codec.hash(request);
        if (requestId != null) {
            store.lockRequest(owner, requestId);
            var cached = store.request(owner, requestId);
            if (cached.isPresent()) {
                if (!cached.get().hash().equals(hash))
                    throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "idempotency key reused");
                return codec.decode(cached.get().response());
            }
        }

        if (!List.of("expense", "income").contains(request.type())) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid transaction type");
        }
        BusinessTimeZone.parse(request.timeZone());
        var account =
                store.lockAccount(owner, request.accountId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ApiErrorCode.NOT_FOUND, "account not found"));
        long balance;
        try {
            balance =
                    request.type().equals("income")
                            ? Math.addExact(account.balance(), request.amountInCents())
                            : Math.subtractExact(account.balance(), request.amountInCents());
        } catch (ArithmeticException exception) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "amount overflow");
        }
        if (balance < 0) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "insufficient balance");
        }
        String categoryName = request.category() == null ? "" : request.category().strip();
        UUID category =
                categoryName.isEmpty() ? null : store.category(owner, request.type(), categoryName);
        UUID id = UUID.randomUUID();
        store.createTransaction(
                id,
                owner,
                account.id(),
                request.type(),
                category,
                request.note() == null ? "" : request.note().strip(),
                request.amountInCents(),
                request.date(),
                request.time());
        store.updateBalance(owner, account.id(), balance);
        var result =
                new FinanceDtos.Transaction(
                        id,
                        account.id(),
                        request.type(),
                        categoryName,
                        request.note() == null ? "" : request.note().strip(),
                        request.amountInCents(),
                        request.date(),
                        request.time(),
                        balance);
        if (requestId != null)
            store.rememberRequest(owner, requestId, hash, id, codec.encode(result));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceDtos.Transaction> day(UUID owner, LocalDate date) {
        return bounds.requireComplete(
                store.between(owner, date, date.plusDays(1), bounds.dayLimit() + 1),
                bounds.dayLimit());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DailySummary> month(UUID owner, YearMonth month) {
        return store.monthSummary(owner, month.atDay(1), month.plusMonths(1).atDay(1));
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(UUID owner) {
        return new Summary(store.totalAssets(owner), store.totalExpense(owner));
    }

    /** 完整支出聚合，不受明细返回条数影响。 */
    @Override
    @Transactional(readOnly = true)
    public long expenseOn(UUID owner, LocalDate date) {
        return store.expenseOn(owner, date);
    }

    /** 单页只读取 limit+1 条以识别是否还有记录。 */
    @Override
    @Transactional(readOnly = true)
    public Page<FinanceDtos.Transaction> page(UUID owner, LocalDate date, int offset, int limit) {
        bounds.validatePage(offset, limit);
        return bounds.page(
                store.between(owner, date, date.plusDays(1), limit + 1, offset), offset, limit);
    }

    /** 账户超限时可从此接口继续读取，不静默丢弃后续账户。 */
    @Override
    @Transactional(readOnly = true)
    public Page<FinanceDtos.Account> accountsPage(UUID owner, int offset, int limit) {
        bounds.validatePage(offset, limit);
        return bounds.page(store.accounts(owner, limit + 1, offset), offset, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public FinanceDtos.MonthOverview overview(UUID owner, YearMonth month) {
        return store.overview(owner, month.atDay(1), month.plusMonths(1).atDay(1));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FinanceDtos.Transaction> monthPage(
            UUID owner,
            YearMonth month,
            String type,
            UUID account,
            String query,
            int offset,
            int limit) {
        bounds.validatePage(offset, limit);
        if (!List.of("", "expense", "income").contains(type) || query.length() > 200)
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid filter");
        return bounds.page(
                store.filtered(
                        owner,
                        month.atDay(1),
                        month.plusMonths(1).atDay(1),
                        type,
                        account,
                        query.strip(),
                        offset,
                        limit + 1),
                offset,
                limit);
    }
}
