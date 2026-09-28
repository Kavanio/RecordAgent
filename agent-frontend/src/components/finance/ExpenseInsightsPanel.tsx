import React, { useState } from 'react';
import type { FinanceChartRange, FinanceExpenseInsights } from '../../types/finance';
import { Button } from '../common/Button';
import { financeCategoryColor } from './financeCategoryColors';
import styles from './ExpenseInsightsPanel.module.css';

const ranges: Array<{ value: FinanceChartRange; label: string }> = [
  { value: 'week', label: '本周' }, { value: 'month', label: '本月' }, { value: 'year', label: '今年' },
];
const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);

interface ExpenseInsightsPanelProps {
  insights: FinanceExpenseInsights | null;
  range: FinanceChartRange;
  loading: boolean;
  error: string | null;
  onRangeChange: (range: FinanceChartRange) => void;
  onRetry: () => void;
  categoryColors?: Record<string, string>;
}

/** 同一自然周期下的账户支出柱图及可切换的单笔、分类支出排行。 */
export const ExpenseInsightsPanel: React.FC<ExpenseInsightsPanelProps> = ({
  insights, range, loading, error, onRangeChange, onRetry, categoryColors,
}) => {
  const [rankingMode, setRankingMode] = useState<'transactions' | 'categories'>('transactions');
  const visible = !loading && !error && insights?.range === range ? insights : null;
  const accounts = visible?.accounts ?? [];
  const entries = rankingMode === 'transactions'
    ? (visible?.transactions ?? []).map((entry) => ({ id: entry.id, label: entry.note || entry.category,
      category: entry.category, amount: entry.amount,
      detail: `${entry.category} · ${entry.date.slice(5)}${entry.source === 'calendar' ? ' · 日历' : ''}` }))
    : (visible?.categories ?? []).map((entry) => ({ id: entry.category, label: entry.category,
      category: entry.category, amount: entry.amount, detail: '' }));
  const accountMax = Math.max(0, ...accounts.map((item) => item.amount));
  const rankMax = Math.max(0, ...entries.map((item) => item.amount));
  const state = error ? <div className={styles.state} role="alert"><span>{error}</span><Button type="button" variant="ghost" size="sm" onClick={onRetry}>重新加载分析</Button></div>
    : loading || !visible ? <p className={styles.state} role="status">正在加载支出分析…</p> : null;

  return <section className={styles.section} aria-label="支出分析">
    <div className={styles.heading}>
      <div><h2>支出分析</h2><p>按自然周、月、年查看账户和支出明细</p></div>
      <div className={styles.switch} role="group" aria-label="支出分析时间范围">
        {ranges.map((option) => <button key={option.value} type="button" className={range === option.value ? styles.active : ''}
          aria-pressed={range === option.value} aria-label={`分析${option.label}`} onClick={() => onRangeChange(option.value)}>{option.label}</button>)}
      </div>
    </div>
    {state ?? <div className={styles.columns}>
      <div className={styles.panel}>
        <div className={styles.panelHeading}><h3>账户支出</h3><span>按资产账户</span></div>
        {accounts.length && accountMax > 0 ? <div className={styles.list} role="list" aria-label="各账户支出">
          {accounts.map((item) => <div key={item.accountId} className={styles.row} role="listitem">
            <div className={styles.rowHeading}><span title={item.accountName}>{item.accountName}</span><strong>{money(item.amount)}</strong></div>
            <div className={styles.track} role="img" aria-label={`${item.accountName}支出${money(item.amount)}`}><span className={styles.accountBar} style={{ width: `${item.amount / accountMax * 100}%` }} /></div>
          </div>)}
        </div> : <p className={styles.empty}>本期暂无账户支出</p>}
        <p className={styles.footnote}>旧日历花销未关联资产账户，不计入此图</p>
      </div>
      <div className={styles.panel}>
        <div className={styles.panelHeading}><h3>支出排行</h3><span>含旧日历花销</span></div>
        <div className={styles.modeSwitch} role="group" aria-label="排行方式">
          <button type="button" aria-pressed={rankingMode === 'transactions'} className={rankingMode === 'transactions' ? styles.modeActive : ''} onClick={() => setRankingMode('transactions')}>单笔支出</button>
          <button type="button" aria-pressed={rankingMode === 'categories'} className={rankingMode === 'categories' ? styles.modeActive : ''} onClick={() => setRankingMode('categories')}>支出分类</button>
        </div>
        {entries.length ? <ol className={styles.ranking}>{entries.map((entry, index) => {
          return <li key={entry.id}>
            <span className={styles.ordinal}>{String(index + 1).padStart(2, '0')}</span>
            <div className={styles.rankBody}><div className={styles.rowHeading}><span title={entry.label}>{entry.label}</span><strong>{money(entry.amount)}</strong></div>
              {entry.detail && <small>{entry.detail}</small>}
              <div className={styles.track} aria-hidden="true"><span className={styles.rankBar} style={{ width: `${entry.amount / rankMax * 100}%`, backgroundColor: financeCategoryColor(entry.category, 'expense', categoryColors) }} /></div>
            </div>
          </li>;
        })}</ol> : <p className={styles.empty}>本期暂无支出记录</p>}
        {rankingMode === 'transactions' && entries.length > 0 && <p className={styles.footnote}>按金额从高到低，共 {entries.length} 笔</p>}
      </div>
    </div>}
  </section>;
};
