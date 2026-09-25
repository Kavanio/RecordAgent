import type {
  CreateFinanceAccountInput,
  CreateFinanceBalanceAdjustmentInput,
  CreateFinanceTransactionInput,
  CreateFinanceTransferInput,
  FinanceAccount,
  FinanceCashflowSummary,
  FinanceChartRange,
  FinanceCategoryOptions,
  FinanceExpenseChart,
  FinanceOverview,
  FinanceTransaction,
  FinanceTransferResponse,
  UpdateFinanceTransactionInput,
} from '../types/finance';
import { getApiBaseUrl, type ApiResponse } from './api';

type CompatibleFinanceExpenseChart = Omit<FinanceExpenseChart, 'totalIncome' | 'days'> & {
  totalIncome?: number;
  days: Array<Omit<FinanceExpenseChart['days'][number], 'income'> & { income?: number }>;
};

async function financeRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${getApiBaseUrl()}${path}`, init);
  let payload: ApiResponse<T>;
  try {
    payload = await response.json() as ApiResponse<T>;
  } catch {
    throw new Error(`财务服务返回了无法识别的响应（HTTP ${response.status}）`);
  }
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.message || `财务请求失败（HTTP ${response.status}）`);
  }
  return payload.data;
}

const jsonInit = (body: object, method = 'POST'): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

/** 查询资产、当月统计及最近流水，后端会在返回前补齐每日收益。 */
export function fetchFinanceOverview(): Promise<FinanceOverview> {
  return financeRequest<FinanceOverview>('/agent/finance/overview');
}

/** 按需查询完整流水列表，避免财务首页承担全部历史数据的渲染成本。 */
export function fetchFinanceTransactions(): Promise<FinanceTransaction[]> {
  return financeRequest<FinanceTransaction[]>('/agent/finance/transactions');
}

/** 查询用户使用过的收入与支出分类及各自已保存的颜色。 */
export function fetchFinanceCategories(): Promise<FinanceCategoryOptions> {
  return financeRequest<FinanceCategoryOptions>('/agent/finance/categories');
}

/** 为尚未提供收入字段的旧版后端响应补齐安全默认值。 */
export function normalizeFinanceExpenseChart(chart: CompatibleFinanceExpenseChart): FinanceExpenseChart {
  return {
    ...chart,
    totalIncome: chart.totalIncome ?? 0,
    days: chart.days.map((day) => ({ ...day, income: day.income ?? 0 })),
  };
}

/** 查询连续日期的每日收支趋势、支出分类堆叠数据和同次读取的分类配色。 */
export async function fetchFinanceExpenseChart(range: FinanceChartRange): Promise<FinanceExpenseChart> {
  const chart = await financeRequest<CompatibleFinanceExpenseChart>(`/agent/finance/expense-chart?range=${range}`);
  return normalizeFinanceExpenseChart(chart);
}

/** 新建微信、支付宝、银行卡、现金或其他账户。 */
export function createFinanceAccount(input: CreateFinanceAccountInput): Promise<FinanceAccount> {
  return financeRequest<FinanceAccount>('/agent/finance/accounts', jsonInit(input));
}

/** 手动校准单个资产账户余额，并生成不参与真实收支统计的审计流水。 */
export function createFinanceBalanceAdjustment(
  accountId: string,
  input: CreateFinanceBalanceAdjustmentInput,
): Promise<FinanceTransaction> {
  return financeRequest<FinanceTransaction>(`/agent/finance/accounts/${encodeURIComponent(accountId)}/adjustments`, jsonInit(input));
}

/** 新建一条关联资产账户的收入或支出。 */
export function createFinanceTransaction(input: CreateFinanceTransactionInput): Promise<FinanceTransaction> {
  return financeRequest<FinanceTransaction>('/agent/finance/transactions', jsonInit(input));
}

/** 修改一条手工收入或支出，后端会原子撤销旧余额影响并应用新值。 */
export function updateFinanceTransaction(
  transactionId: string,
  input: UpdateFinanceTransactionInput,
): Promise<FinanceTransaction> {
  return financeRequest<FinanceTransaction>(`/agent/finance/transactions/${encodeURIComponent(transactionId)}`, jsonInit(input, 'PUT'));
}

/** 在两个资产账户之间进行划账并同步调整双方余额。 */
export function createFinanceTransfer(input: CreateFinanceTransferInput): Promise<FinanceTransferResponse> {
  return financeRequest<FinanceTransferResponse>('/agent/finance/transfers', jsonInit(input));
}

/** 按日查询发生变化的资产流水，包含划账与余额校准。 */
export function fetchFinanceDayTransactions(date: string): Promise<FinanceTransaction[]> {
  return financeRequest<FinanceTransaction[]>(`/agent/finance/transactions/day?date=${encodeURIComponent(date)}`);
}

/** 月历仅查询收支合计，不加载整月完整流水。 */
export function fetchFinanceCashflowSummary(from: string, to: string): Promise<FinanceCashflowSummary> {
  return financeRequest<FinanceCashflowSummary>(`/agent/finance/cashflow-summary?${new URLSearchParams({ from, to })}`);
}
