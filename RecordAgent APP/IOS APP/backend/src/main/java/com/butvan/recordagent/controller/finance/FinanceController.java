package com.butvan.recordagent.controller.finance;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.security.AuthenticatedUser;
import com.butvan.recordagent.service.finance.FinanceService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 财务账户与流水的受认证 HTTP 入口。 */
@RestController
@RequestMapping("/api/v1/finance")
@RequiredArgsConstructor
public class FinanceController {
    private final FinanceService finance;

    @ApiLog("读取本人财务账户")
    @GetMapping("/accounts")
    public List<FinanceDtos.Account> accounts(@AuthenticationPrincipal AuthenticatedUser user) {
        return finance.accounts(user.userId());
    }

    @ApiLog("创建财务账户")
    @PostMapping("/accounts")
    public FinanceDtos.Account createAccount(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody FinanceDtos.CreateAccount request) {
        return finance.createAccount(user.userId(), request);
    }

    @ApiLog("记录收支流水")
    @PostMapping("/transactions")
    public FinanceDtos.Transaction createTransaction(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody FinanceDtos.CreateTransaction request,
            @RequestHeader(value = "Idempotency-Key", required = false) UUID requestId) {
        return finance.createTransaction(user.userId(), request, requestId);
    }

    @ApiLog("读取当日收支流水")
    @GetMapping("/transactions")
    public List<FinanceDtos.Transaction> transactions(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam LocalDate date) {
        return finance.day(user.userId(), date);
    }

    /** 分页读取账户；列表超过上限时用 nextOffset 继续读取。 */
    @ApiLog("分页读取本人财务账户")
    @GetMapping("/accounts/page")
    public Page<FinanceDtos.Account> accountsPage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        return finance.accountsPage(user.userId(), offset, limit);
    }

    @ApiLog("读取本人财务月度总览")
    @GetMapping("/overview")
    public FinanceDtos.MonthOverview overview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam java.time.YearMonth month) {
        return finance.overview(user.userId(), month);
    }

    @ApiLog("分页筛选本人月度流水")
    @GetMapping("/transactions/page")
    public Page<FinanceDtos.Transaction> monthPage(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam java.time.YearMonth month,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return finance.monthPage(user.userId(), month, type, accountId, query, offset, limit);
    }
}
