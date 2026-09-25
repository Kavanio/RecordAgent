import { ArrowDownLeftIcon, ArrowUpRightIcon, CaretRightIcon, SparkleIcon, WalletIcon } from '@phosphor-icons/react';
import type { FinanceOverview } from '../../types/finance';
import { Button } from '../common/Button';
import { LoadingTree } from '../common/LoadingTree';
import styles from './FinanceSummary.module.css';

interface FinanceSummaryProps {
  overview: FinanceOverview | null;
  loading: boolean;
  error: string | null;
  onRetry: () => void;
  onOpenAssets: () => void;
}

const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);

/** 资产与月度收支摘要；未知金额不显示为零，刷新失败时明确标记上次数据。 */
export function FinanceSummary({ overview, loading, error, onRetry, onOpenAssets }: FinanceSummaryProps) {
  return <section className={styles.summary} aria-label="本月财务概览" aria-busy={loading}>
    {error && <div className={styles.error} role="alert">
      <div><strong>{overview ? '更新未完成，以下为上次读取的数据' : '财务概览加载失败'}</strong><p>{error}</p></div>
      <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={loading}>重新加载概览</Button>
    </div>}
    {!overview ? (!error && <div className={styles.loading}><LoadingTree size="small" label="正在读取资产与收支…" /></div>) : <div className={styles.metrics}>
      <button type="button" className={styles.assets} onClick={onOpenAssets} aria-label="查看全部资产详情">
        <span className={styles.label}><WalletIcon size={16} />总资产</span>
        <strong className={styles.assetAmount}>{money(overview.totalAssets)}</strong>
        <span className={styles.assetFoot}>{overview.accounts.length} 个资产账户<span>查看详情<CaretRightIcon size={12} /></span></span>
      </button>
      <dl className={styles.monthStats}>
        <div className={styles.income}><dt><ArrowDownLeftIcon size={15} />本月收入</dt><dd>{money(overview.monthIncome)}</dd><small>含自动计提收益</small></div>
        <div className={styles.expense}><dt><ArrowUpRightIcon size={15} />本月支出</dt><dd>{money(overview.monthExpense)}</dd><small>日常消费与支出</small></div>
        <div className={styles.yield}><dt><SparkleIcon size={15} />理财收益</dt><dd>{money(overview.monthYield)}</dd><small>本月累计计提</small></div>
      </dl>
    </div>}
  </section>;
}
