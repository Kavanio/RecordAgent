package butvan.agent.network.finance.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.finance.dto.FinanceDtos;
import butvan.agent.network.finance.dto.FinanceDtos.AccountResponse;
import butvan.agent.network.finance.dto.FinanceDtos.CreateAccountRequest;
import butvan.agent.network.finance.dto.FinanceDtos.CreateBalanceAdjustmentRequest;
import butvan.agent.network.finance.dto.FinanceDtos.CreateTransactionRequest;
import butvan.agent.network.finance.dto.FinanceDtos.CreateTransferRequest;
import butvan.agent.network.finance.dto.FinanceDtos.OverviewResponse;
import butvan.agent.network.finance.dto.FinanceDtos.TransactionResponse;
import butvan.agent.network.finance.dto.FinanceDtos.TransferResponse;
import butvan.agent.network.finance.dto.FinanceDtos.UpdateTransactionRequest;
import butvan.agent.network.finance.model.FinanceModels.FinanceTransaction;
import butvan.agent.network.finance.service.FinanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 财务模块 HTTP 协议适配层。 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/agent/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final FinanceService financeService;
    private final CurrentUserProvider currentUserProvider;

    @ApiLog("查询资产与收支总览")
    @GetMapping("/overview")
    public Result<OverviewResponse> overview() {
        return Result.success(FinanceDtos.from(financeService.getOverview(currentUserProvider.currentUserId())));
    }

    @ApiLog("查询全部财务流水")
    @GetMapping("/transactions")
    public Result<List<TransactionResponse>> transactions() {
        return Result.success(financeService.getTransactions(currentUserProvider.currentUserId()).stream()
                .map(FinanceDtos::from).toList());
    }

    /** 当前用户指定自然日的资产流水；无记录日期返回空列表，非法日期返回参数错误。 */
    @ApiLog("查询当日资产变动")
    @GetMapping("/transactions/day")
    public Result<List<TransactionResponse>> dayTransactions(@RequestParam java.time.LocalDate date) {
        return Result.success(financeService.getDayTransactions(currentUserProvider.currentUserId(), date)
                .stream().map(FinanceDtos::from).toList());
    }

    /** 当前用户的闭区间自然日收支，仅返回汇总数字。 */
    @ApiLog("查询日期范围收支汇总")
    @GetMapping("/cashflow-summary")
    public Result<FinanceDtos.CashflowSummaryResponse> cashflowSummary(
            @RequestParam java.time.LocalDate from, @RequestParam java.time.LocalDate to) {
        var summary = financeService.getCashflowSummary(currentUserProvider.currentUserId(), from, to);
        return Result.success(new FinanceDtos.CashflowSummaryResponse(summary.income(), summary.expense()));
    }

    @ApiLog("查询收支分类")
    @GetMapping("/categories")
    public Result<FinanceDtos.TransactionCategoryOptionsResponse> categories() {
        String ownerId = currentUserProvider.currentUserId();
        return Result.success(FinanceDtos.categoryOptions(
                financeService.getTransactionCategories(ownerId), financeService.getTransactionCategoryColors(ownerId)));
    }

    @ApiLog("查询收支趋势与支出分类构成")
    @GetMapping("/expense-chart")
    public Result<FinanceDtos.ExpenseChartResponse> expenseChart(
            @RequestParam(defaultValue = "month") String range) {
        return Result.success(FinanceDtos.from(
                financeService.getExpenseChart(currentUserProvider.currentUserId(), range)));
    }

    @ApiLog("创建资产账户")
    @PostMapping("/accounts")
    public Result<AccountResponse> createAccount(@RequestBody CreateAccountRequest request) {
        return Result.success(FinanceDtos.from(financeService.createAccount(
                currentUserProvider.currentUserId(), request.name(), request.accountType(), request.currency(),
                request.initialBalance(), request.interestEnabled(), request.annualRatePercent())));
    }

    @ApiLog("手动校准资产账户余额")
    @PostMapping("/accounts/{accountId}/adjustments")
    public Result<TransactionResponse> adjustAccountBalance(
            @PathVariable String accountId, @RequestBody CreateBalanceAdjustmentRequest request) {
        return Result.success(FinanceDtos.from(financeService.adjustAccountBalance(
                currentUserProvider.currentUserId(), accountId, request.direction(), request.amount(), request.note())));
    }

    @ApiLog("创建收入或支出流水")
    @PostMapping("/transactions")
    public Result<TransactionResponse> createTransaction(@RequestBody CreateTransactionRequest request) {
        return Result.success(FinanceDtos.from(financeService.createTransaction(
                currentUserProvider.currentUserId(), request.accountId(), request.transactionType(),
                request.category(), request.note(), request.amount(), request.date(), request.time(), request.categoryColor())));
    }

    @ApiLog("修改手工收入或支出流水")
    @PutMapping("/transactions/{transactionId}")
    public Result<TransactionResponse> updateTransaction(
            @PathVariable String transactionId, @RequestBody UpdateTransactionRequest request) {
        return Result.success(FinanceDtos.from(financeService.updateTransaction(
                currentUserProvider.currentUserId(), transactionId, request.accountId(), request.transactionType(),
                request.category(), request.note(), request.amount(), request.date(), request.time(), request.categoryColor())));
    }

    @ApiLog("账户间资产划账")
    @PostMapping("/transfers")
    public Result<TransferResponse> transfer(@RequestBody CreateTransferRequest request) {
        List<FinanceTransaction> transactions = financeService.transfer(
                currentUserProvider.currentUserId(), request.fromAccountId(), request.toAccountId(),
                request.amount(), request.note(), request.date(), request.time());
        return Result.success(FinanceDtos.from(transactions.get(0), transactions.get(1)));
    }
}
