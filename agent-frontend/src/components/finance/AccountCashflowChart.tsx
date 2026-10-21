import React, { useEffect, useId, useRef, useState } from 'react';
import type { FinanceAccountType, FinanceExpenseInsights } from '../../types/finance';
import { ChartSkeleton } from '../common/ChartSkeleton';
import { Skeleton } from '../common/Skeleton';
import { FinanceChartCard } from './FinanceChartCard';
import { FinanceChartTabs } from './FinanceChartTabs';
import styles from './AccountCashflowChart.module.css';

const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
const amount = (value: number) => new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value);
const compact = (value: number) => value >= 10000 ? `${Number((value / 10000).toFixed(1))}万` : String(Number(value.toFixed(2)));
const modes = [{ value: 'expense', label: '支出' }, { value: 'income', label: '收入' }] as const;

const accountBrand = (type: FinanceAccountType | undefined, name: string) => {
  // 兼容尚未更新的后端响应；旧响应没有 accountType，只能从账户名称识别品牌。
  if (type?.startsWith('wechat') || (!type && /微信/.test(name))) return 'wechat';
  if (type?.startsWith('alipay') || (!type && /支付宝/.test(name))) return 'alipay';
  if (type && type !== 'bank') return 'other';
  if (/招商银行|招行/.test(name)) return 'cmb';
  if (/农业银行|农行/.test(name)) return 'abc';
  if (/建设银行|建行/.test(name)) return 'ccb';
  return 'bank';
};

interface AccountCashflowChartProps {
  accounts: FinanceExpenseInsights['accounts'];
  loading?: boolean;
  mode: 'expense' | 'income';
  onModeChange: (mode: 'expense' | 'income') => void;
}

/** 账户收支柱图：金额优先、低饱和品牌色与鼠标和键盘共用的金额明细。 */
export const AccountCashflowChart: React.FC<AccountCashflowChartProps> = ({ accounts, loading = false, mode, onModeChange }) => {
  const host = useRef<HTMLDivElement>(null);
  const tooltipId = useId();
  const [active, setActive] = useState<{ id: string; left: number } | null>(null);
  useEffect(() => { if (loading) setActive(null); }, [loading]);
  const label = mode === 'expense' ? '支出' : '收入';
  const rows = accounts.map((account) => ({ ...account, value: mode === 'expense' ? account.amount : account.income }));
  const maximum = Math.max(0, ...rows.map((item) => item.value));
  const unit = maximum > 0 ? 10 ** Math.floor(Math.log10(maximum)) : 1;
  const ceiling = maximum > 0 ? Math.ceil(maximum * 1.25 / unit) * unit : 1;
  const total = rows.reduce((sum, item) => sum + item.value, 0);
  const activeCount = rows.filter((item) => item.value > 0).length;
  const leadingAccount = rows.reduce<(typeof rows)[number] | undefined>((leading, item) => (
    item.value > (leading?.value ?? 0) ? item : leading
  ), undefined);
  const selected = rows.find((item) => item.accountId === active?.id);
  const show = (id: string, element: HTMLElement) => {
    const bounds = host.current?.getBoundingClientRect();
    if (!bounds) return;
    const target = element.getBoundingClientRect();
    setActive({ id, left: Math.max(90, Math.min(bounds.width - 90, target.left - bounds.left + target.width / 2)) });
  };
  const restoreFocusedAccount = () => {
    const focused = document.activeElement;
    if (focused instanceof HTMLElement && host.current?.contains(focused) && focused.dataset.accountId) {
      show(focused.dataset.accountId, focused);
      return;
    }
    setActive(null);
  };

  return <FinanceChartCard title={`账户${label}`} subtitle="按账户查看资金分布" label="账户收支分析"
    actions={<FinanceChartTabs label="账户收支类型" value={mode} options={modes}
      onChange={(next) => { setActive(null); onModeChange(next); }} />}
    footer={mode === 'income' ? '包含自动收益，不含账户划转与余额校准' : '旧日历花销未关联资产账户，不计入此图'}>
    {loading ? <div className={styles.loadingContent} role="status" aria-label={`正在加载账户${label}分析`}>
      <Skeleton variant="text" decorative />
      <div className={styles.loadingColumns}><ChartSkeleton variant="bars" label={`正在读取账户${label}分布…`} decorative /></div>
    </div> : <>
    <div className={styles.summary}>
      <div className={styles.total}>
        <span>账户{label}合计</span>
        <div className={styles.amount} aria-label={`账户${label}合计${money(total)}`}><span>¥</span><strong>{amount(total)}</strong></div>
      </div>
      <div className={styles.accountCount}><strong>{activeCount}<span> / {rows.length}</span></strong><span>有{label}账户</span></div>
    </div>
    {maximum > 0 ? <><div ref={host} className={styles.chart} data-dense={rows.length > 6} role="group" aria-label={`账户${label}柱状图`} onMouseLeave={restoreFocusedAccount}>
      <span className={styles.unit}>元</span>
      <div className={styles.yAxis} aria-hidden="true">{[1, .75, .5, .25, 0].map((ratio) => <span key={ratio}>{compact(ceiling * ratio)}</span>)}</div>
      <div className={styles.plotViewport}>
        <div className={styles.plot}>
          <div className={styles.grid} aria-hidden="true">{[0, 1, 2, 3, 4].map((tick) => <span key={tick} />)}</div>
          <div className={styles.bars} data-active={active !== null}
            style={{ gap: Math.min(12, 50 / Math.max(1, rows.length - 1)) }}>
            {rows.map((item, index) => <button type="button" key={item.accountId} className={styles.barGroup}
              data-brand={accountBrand(item.accountType, item.accountName)} data-selected={active?.id === item.accountId}
              data-account-id={item.accountId}
              aria-label={`${item.accountName}${label}${money(item.value)}`}
              aria-describedby={active?.id === item.accountId ? tooltipId : undefined}
              onMouseEnter={(event) => show(item.accountId, event.currentTarget)}
              onFocus={(event) => show(item.accountId, event.currentTarget)} onBlur={() => setActive(null)}
              onKeyDown={(event) => { if (event.key === 'Escape') setActive(null); }}>
              <span className={styles.barSlot}>
                <span className={styles.barValue} style={{ bottom: `calc(${item.value / ceiling * 100}% + 10px)` }}>{compact(item.value)}</span>
                <span className={styles.barEnvelope} style={{ height: `${item.value / ceiling * 100}%`, minHeight: item.value > 0 ? 5 : undefined }}>
                  <span className={styles.bar} style={{ animationDelay: `${Math.min(index, 8) * 35}ms` }} />
                </span>
              </span>
              <span className={styles.xLabel} title={item.accountName}>{item.accountName}</span>
            </button>)}
          </div>
        </div>
      </div>
      {selected && active && <div id={tooltipId} role="tooltip" className={styles.tooltip} style={{ left: active.left }}>
        <span>{selected.accountName}</span><strong>{money(selected.value)}</strong>
        <small>占账户{label} {total > 0 ? (selected.value / total * 100).toFixed(1) : '0.0'}%</small>
      </div>}
    </div>
      {leadingAccount && <div className={styles.insight}>
        <div><span className={styles.insightLabel}>主要{label}账户</span><strong title={leadingAccount.accountName}>{leadingAccount.accountName}</strong></div>
        <span className={styles.insightShare}>占比 <strong>{(leadingAccount.value / total * 100).toFixed(1)}<span>%</span></strong></span>
      </div>}
    </> : <div className={styles.empty}>
      <span className={styles.emptyGlyph} aria-hidden="true"><i /><i /><i /></span>
      <p>本期暂无账户{label}</p><span>关联账户的{label}会在这里汇总</span>
    </div>}
    </>}
  </FinanceChartCard>;
};
