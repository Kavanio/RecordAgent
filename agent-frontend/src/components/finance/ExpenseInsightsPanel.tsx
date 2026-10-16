import React, { useState } from 'react';
import type { FinanceChartRange, FinanceExpenseInsights } from '../../types/finance';
import { Button } from '../common/Button';
import { AccountCashflowChart } from './AccountCashflowChart';
import { ExpenseRankingChart } from './ExpenseRankingChart';
import { FinanceChartTabs } from './FinanceChartTabs';
import styles from './ExpenseInsightsPanel.module.css';

const ranges: Array<{ value: FinanceChartRange; label: string; ariaLabel: string }> = [
  { value: 'week', label: '本周', ariaLabel: '分析本周' },
  { value: 'month', label: '本月', ariaLabel: '分析本月' },
  { value: 'year', label: '今年', ariaLabel: '分析今年' },
];

interface ExpenseInsightsPanelProps {
  insights: FinanceExpenseInsights | null;
  range: FinanceChartRange;
  loading: boolean;
  error: string | null;
  onRangeChange: (range: FinanceChartRange) => void;
  onRetry: () => void;
  categoryColors?: Record<string, string>;
}

/** 统一编排同一自然周期的账户收支与支出排行；切换时隐藏旧周期数据。 */
export const ExpenseInsightsPanel: React.FC<ExpenseInsightsPanelProps> = ({
  insights, range, loading, error, onRangeChange, onRetry, categoryColors,
}) => {
  const [accountMode, setAccountMode] = useState<'expense' | 'income'>('expense');
  const [rankingMode, setRankingMode] = useState<'transactions' | 'categories'>('transactions');
  const visible = !loading && !error && insights?.range === range ? insights : null;
  return <section className={styles.section} aria-label="支出分析">
    <div className={styles.heading}>
      <div><h2>收支分析</h2><p>{visible ? `${visible.from.replaceAll('-', '.')} — ${visible.to.replaceAll('-', '.')}` : '按自然周、月、年查看账户收支与支出排行'}</p></div>
      <FinanceChartTabs label="支出分析时间范围" value={range} options={ranges} onChange={onRangeChange} />
    </div>
    {error ? <div className={styles.state} role="alert"><span>{error}</span><Button type="button" variant="ghost" size="sm" onClick={onRetry}>重新加载分析</Button></div> : <div className={styles.columns}>
      <AccountCashflowChart accounts={visible?.accounts ?? []} loading={!visible} mode={accountMode} onModeChange={setAccountMode} />
      <ExpenseRankingChart insights={visible} mode={rankingMode} onModeChange={setRankingMode} categoryColors={categoryColors} />
    </div>}
  </section>;
};
