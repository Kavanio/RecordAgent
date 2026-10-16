export type FinanceAccountType =
  | 'wechat_balance' | 'wechat_yield' | 'alipay_balance' | 'alipay_yuebao' | 'bank' | 'other'
  | 'wechat' | 'alipay' | 'cash';
export type FinanceTransactionType =
  | 'income' | 'expense' | 'yield' | 'transfer_out' | 'transfer_in'
  | 'adjustment_increase' | 'adjustment_decrease';
export type FinanceChartRange = 'today' | 'week' | 'month' | 'year';

export interface FinanceAccount {
  id: string;
  name: string;
  accountType: FinanceAccountType;
  currency: string;
  balance: number;
  interestEnabled: boolean;
  annualRatePercent: number;
  lastAccrualDate: string;
  version: number;
}

export interface FinanceTransaction {
  id: string;
  accountId: string;
  accountName: string;
  date: string;
  time: string;
  transactionType: FinanceTransactionType;
  category: string;
  note: string;
  amount: number;
  currency: string;
  source: 'manual' | 'automatic' | 'calendar' | 'adjustment';
  createdAt: string;
}

export interface FinanceOverview {
  totalAssets: number;
  monthIncome: number;
  monthExpense: number;
  monthYield: number;
  accounts: FinanceAccount[];
  transactions: FinanceTransaction[];
}

export interface FinanceCategoryOptions {
  expense: string[];
  income: string[];
  /** 旧版响应可缺省；颜色按用户、收入/支出类型及分类名称保存。 */
  colors?: Partial<Record<'expense' | 'income', Record<string, string>>>;
}

export interface FinanceExpenseCategory {
  category: string;
  amount: number;
}

export interface FinanceExpenseChartDay {
  date: string;
  total: number;
  income: number;
  categories: FinanceExpenseCategory[];
}

export interface FinanceExpenseChart {
  range: FinanceChartRange;
  from: string;
  to: string;
  totalExpense: number;
  totalIncome: number;
  days: FinanceExpenseChartDay[];
  /** 与图表一起读取的分类配色，避免独立分类请求导致颜色闪变。 */
  categoryColors?: Record<string, string>;
}

/** 账户支出与两种排行的同期间数据；账户图仅包含已绑定账户的支出。 */
export interface FinanceExpenseInsights {
  range: FinanceChartRange;
  from: string;
  to: string;
  totalExpense: number;
  accounts: Array<{ accountId: string; accountName: string; accountType?: FinanceAccountType; amount: number; income: number }>;
  transactions: Array<{ id: string; note: string; category: string; date: string; amount: number; source: string }>;
  categories: FinanceExpenseCategory[];
}

export interface CreateFinanceAccountInput {
  name: string;
  accountType: FinanceAccountType;
  currency: string;
  initialBalance: number;
  interestEnabled: boolean;
  annualRatePercent: number;
}

export interface CreateFinanceBalanceAdjustmentInput {
  direction: 'increase' | 'decrease';
  amount: number;
  note: string;
}

export interface CreateFinanceTransactionInput {
  accountId: string;
  transactionType: 'income' | 'expense';
  category: string;
  /** 仅显式选色时提交；省略时保留已保存的分类颜色。 */
  categoryColor?: string;
  note: string;
  amount: number;
  date: string;
  time: string;
}

export type UpdateFinanceTransactionInput = CreateFinanceTransactionInput;

export interface CreateFinanceTransferInput {
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  note?: string;
  date: string;
  time: string;
}

export interface FinanceTransferResponse {
  fromTransaction: FinanceTransaction;
  toTransaction: FinanceTransaction;
}

/** 任意自然日期间的轻量收支汇总。 */
export interface FinanceCashflowSummary { income: number; expense: number; }
