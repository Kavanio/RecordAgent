package com.butvan.recordagent.service.finance.impl;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.repository.finance.FinanceRepository;
import com.butvan.recordagent.service.finance.FinanceService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
    public List<FinanceDtos.Account> accounts(UUID owner) { return store.accounts(owner); }

    @Override
    @Transactional
    public FinanceDtos.Transaction createTransaction(UUID owner, FinanceDtos.CreateTransaction request) {
        if (!List.of("expense", "income").contains(request.type())) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid transaction type");
        }
        try { ZoneId.of(request.timeZone()); }
        catch (Exception exception) { throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "invalid time zone"); }
        var account = store.lockAccount(owner, request.accountId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "account not found"));
        long balance;
        try {
            balance = request.type().equals("income")
                    ? Math.addExact(account.balance(), request.amountInCents())
                    : Math.subtractExact(account.balance(), request.amountInCents());
        } catch (ArithmeticException exception) {
            throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "amount overflow");
        }
        if (balance < 0) throw new ApiException(ApiErrorCode.INVALID_ARGUMENT, "insufficient balance");
        String categoryName = request.category() == null ? "" : request.category().strip();
        UUID category = categoryName.isEmpty() ? null : store.category(owner, request.type(), categoryName);
        UUID id = UUID.randomUUID();
        store.createTransaction(id, owner, account.id(), request.type(), category,
                request.note() == null ? "" : request.note().strip(), request.amountInCents(),
                request.date(), request.time());
        store.updateBalance(owner, account.id(), balance);
        return new FinanceDtos.Transaction(id, account.id(), request.type(),
                categoryName, request.note() == null ? "" : request.note().strip(),
                request.amountInCents(), request.date(), request.time(), balance);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceDtos.Transaction> day(UUID owner, LocalDate date) {
        return store.between(owner, date, date.plusDays(1), 200);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceDtos.Transaction> month(UUID owner, YearMonth month) {
        return store.between(owner, month.atDay(1), month.plusMonths(1).atDay(1), 1000);
    }

    @Override
    @Transactional(readOnly = true)
    public Summary summary(UUID owner) {
        return new Summary(store.totalAssets(owner), store.totalExpense(owner));
    }
}
