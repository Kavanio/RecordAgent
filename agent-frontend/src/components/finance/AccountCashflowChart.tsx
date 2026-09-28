import React, { useId, useRef, useState } from 'react';
import type { FinanceAccountType, FinanceExpenseInsights } from '../../types/finance';
import styles from './AccountCashflowChart.module.css';

const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
const compact = (value: number) => value >= 10000 ? `${Number((value / 10000).toFixed(1))}万` : String(Number(value.toFixed(2)));

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
  mode: 'expense' | 'income';
  onModeChange: (mode: 'expense' | 'income') => void;
  period: string;
}

/** 账户收支柱图：按账户品牌着色、错峰入场及鼠标和键盘共用的金额明细。 */
export const AccountCashflowChart: React.FC<AccountCashflowChartProps> = ({ accounts, mode, onModeChange, period }) => {
  const host = useRef<HTMLDivElement>(null);
  const tooltipId = useId();
  const [active, setActive] = useState<{ id: string; left: number } | null>(null);
  const label = mode === 'expense' ? '支出' : '收入';
  const rows = accounts.map((account) => ({ ...account, value: mode === 'expense' ? account.amount : account.income }));
  const maximum = Math.max(0, ...rows.map((item) => item.value));
  const unit = maximum > 0 ? 10 ** Math.floor(Math.log10(maximum)) : 1;
  const ceiling = maximum > 0 ? Math.ceil(maximum * 1.25 / unit) * unit : 1;
  const total = rows.reduce((sum, item) => sum + item.value, 0);
  const selected = rows.find((item) => item.accountId === active?.id);
  const show = (id: string, element: HTMLElement) => {
    const bounds = host.current?.getBoundingClientRect();
    if (!bounds) return;
    const target = element.getBoundingClientRect();
    setActive({ id, left: Math.max(90, Math.min(bounds.width - 90, target.left - bounds.left + target.width / 2)) });
  };

  return <section className={styles.section} aria-label="账户收支分析">
    <div className={styles.heading}><h3>账户{label}</h3>
      <div className={styles.switch} role="group" aria-label="账户收支类型">
        <button type="button" aria-pressed={mode === 'expense'} onClick={() => { setActive(null); onModeChange('expense'); }}>支出</button>
        <button type="button" aria-pressed={mode === 'income'} onClick={() => { setActive(null); onModeChange('income'); }}>收入</button>
      </div>
    </div>
    <div className={styles.summary}><span>账户{label}合计</span><strong>{money(total)}</strong></div>
    {maximum > 0 ? <div ref={host} className={styles.chart} role="group" aria-label={`账户${label}柱状图`} onMouseLeave={() => setActive(null)}>
      <span className={styles.unit}>元</span>
      <div className={styles.yAxis} aria-hidden="true">{[1, .75, .5, .25, 0].map((ratio) => <span key={ratio}>{compact(ceiling * ratio)}</span>)}</div>
      <div className={styles.scroll} onScroll={() => setActive(null)}>
        <div className={styles.plot} style={{ minWidth: Math.max(240, rows.length * 78) }}>
          <div className={styles.grid} aria-hidden="true">{[0, 1, 2, 3, 4].map((tick) => <span key={tick} />)}</div>
          <div key={`${mode}-${period}`} className={styles.bars} data-active={active !== null}>
            {rows.map((item, index) => <button type="button" key={item.accountId} className={styles.barGroup}
              data-brand={accountBrand(item.accountType, item.accountName)} data-selected={active?.id === item.accountId}
              aria-label={`${item.accountName}${label}${money(item.value)}`}
              aria-describedby={active?.id === item.accountId ? tooltipId : undefined}
              onMouseEnter={(event) => show(item.accountId, event.currentTarget)}
              onFocus={(event) => show(item.accountId, event.currentTarget)} onBlur={() => setActive(null)}
              onKeyDown={(event) => { if (event.key === 'Escape') setActive(null); }}>
              <span className={styles.barSlot}>
                <span className={styles.barValue} style={{ bottom: `calc(${item.value / ceiling * 100}% + 9px)` }}>{compact(item.value)}</span>
                <span className={styles.barEnvelope} style={{ height: `${item.value / ceiling * 100}%`, minHeight: item.value > 0 ? 4 : undefined }}>
                  <span className={styles.bar} style={{ animationDelay: `${Math.min(index, 8) * 55}ms` }} />
                </span>
              </span>
              <span className={styles.xLabel}>{item.accountName}</span>
            </button>)}
          </div>
        </div>
      </div>
      {selected && active && <div id={tooltipId} role="tooltip" className={styles.tooltip} style={{ left: active.left }}>
        <span>{selected.accountName}</span><strong>{money(selected.value)}</strong>
        <small>占账户{label} {total > 0 ? (selected.value / total * 100).toFixed(1) : '0.0'}%</small>
      </div>}
    </div> : <p className={styles.empty}>本期暂无账户{label}</p>}
    <p className={styles.note}>{mode === 'income' ? '包含自动收益，不含账户划转与余额校准' : '旧日历花销未关联资产账户，不计入此图'}</p>
  </section>;
};
