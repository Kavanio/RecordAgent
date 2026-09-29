import { ChartBarHorizontalIcon } from '@phosphor-icons/react';
import type { CSSProperties } from 'react';
import type { FinanceExpenseInsights } from '../../types/finance';
import { FinanceChartCard } from './FinanceChartCard';
import { FinanceChartTabs } from './FinanceChartTabs';
import { financeCategoryColor } from './financeCategoryColors';
import styles from './ExpenseRankingChart.module.css';

type RankingMode = 'transactions' | 'categories';

interface ExpenseRankingChartProps {
  insights: FinanceExpenseInsights | null;
  mode: RankingMode;
  onModeChange: (mode: RankingMode) => void;
  categoryColors?: Record<string, string>;
}

const modes = [{ value: 'transactions', label: '单笔支出' }, { value: 'categories', label: '支出分类' }] as const;
const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
const amount = (value: number) => new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);
const percentage = (value: number) => value > 0 && value < .1 ? '<0.1%' : `${value.toFixed(1)}%`;

/** 支出排行榜：以全口径支出计算占比，金额、分类配色与来源始终保持一致。 */
export function ExpenseRankingChart({ insights, mode, onModeChange, categoryColors }: ExpenseRankingChartProps) {
  const entries = !insights ? [] : mode === 'transactions'
    ? insights.transactions.map((entry) => ({ id: entry.id, label: entry.note || entry.category,
      category: entry.category, amount: entry.amount,
      detail: `${entry.category} · ${entry.date.slice(5).replace('-', '/')}${entry.source === 'calendar' ? ' · 日历' : ''}` }))
    : insights.categories.map((entry) => ({ id: entry.category, label: entry.category,
      category: entry.category, amount: entry.amount, detail: '' }));
  const countLabel = mode === 'transactions' ? '笔支出' : '个分类';

  return <FinanceChartCard title="支出排行" subtitle="看清每一笔，了解钱花在哪里"
    actions={<FinanceChartTabs label="排行方式" value={mode} options={modes} onChange={onModeChange} />}
    footer={<><span>按金额从高到低</span><span>含旧日历花销</span></>}>
    {!insights ? <div className={styles.loadingContent} role="status" aria-label="正在加载支出排行">
      <span className={styles.loadingAmount} aria-hidden="true" />
      <div className={styles.loadingRows} aria-hidden="true"><i /><i /><i /></div>
    </div> : <>
    <div className={styles.summary}>
      <div className={styles.total}><span>本期支出合计</span>
        <div className={styles.amount} aria-label={`本期支出合计${money(insights.totalExpense)}`}><span>¥</span><strong>{amount(insights.totalExpense)}</strong></div>
      </div>
      <div className={styles.count}><strong>{entries.length}</strong><span>{countLabel}</span></div>
    </div>
    {entries.length > 0 ? <>
      <div className={styles.listHeading}><span>{mode === 'transactions' ? '支出明细' : '分类分布'}</span><span>金额 / 占本期支出</span></div>
      <ol className={styles.ranking} aria-label={mode === 'transactions' ? '单笔支出排行' : '支出分类排行'} tabIndex={0}>
        {entries.map((entry, index) => {
          const share = insights.totalExpense > 0 ? Math.max(0, Math.min(100, entry.amount / insights.totalExpense * 100)) : 0;
          return <li key={entry.id} className={styles.row}
            style={{ '--category-color': financeCategoryColor(entry.category, 'expense', categoryColors) } as CSSProperties}>
            <span className={styles.ordinal} data-leading={index === 0}>{String(index + 1).padStart(2, '0')}</span>
            <div className={styles.rankBody}>
              <div className={styles.rowHeading}><span title={entry.label}>{entry.label}</span><strong>{money(entry.amount)}</strong></div>
              <div className={styles.detail}><span title={entry.detail || entry.category}>
                <i className={styles.categoryDot} aria-hidden="true" />{entry.detail || '分类支出'}</span><span>{percentage(share)}</span></div>
              <div className={styles.track} aria-hidden="true"><span className={styles.bar} style={{ width: `${share}%` }} /></div>
            </div>
          </li>;
        })}
      </ol>
    </> : <div className={styles.empty}><ChartBarHorizontalIcon size={26} weight="light" aria-hidden="true" /><strong>本期暂无支出记录</strong><span>记录支出后，在这里查看分布与排行</span></div>}
    </>}
  </FinanceChartCard>;
}
