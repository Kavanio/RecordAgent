import styles from './FinanceChartTabs.module.css';

interface FinanceChartTabsProps<T extends string> {
  label: string;
  value: T;
  options: readonly { value: T; label: string; ariaLabel?: string }[];
  onChange: (value: T) => void;
}

/** 财务图表共用的受控分段按钮；原生按钮支持键盘操作及选中状态朗读。 */
export function FinanceChartTabs<T extends string>({ label, value, options, onChange }: FinanceChartTabsProps<T>) {
  return <div className={styles.tabs} role="group" aria-label={label}>
    {options.map((option) => <button key={option.value} type="button"
      aria-pressed={value === option.value} aria-label={option.ariaLabel}
      onClick={() => onChange(option.value)}>{option.label}</button>)}
  </div>;
}
