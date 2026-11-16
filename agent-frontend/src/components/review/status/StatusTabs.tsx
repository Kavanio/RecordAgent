import { STATUS_TABS, type StatusTab } from '../../../features/review/statusPresentation';
import styles from './StatusTabs.module.css';

interface StatusTabsProps {
  value: StatusTab;
  onChange: (value: StatusTab) => void;
}

/** 状态模块三分段切换（今天 / 认知 / 足迹），支持左右方向键切换。 */
export function StatusTabs({ value, onChange }: StatusTabsProps) {
  function move(step: number) {
    const index = STATUS_TABS.findIndex(tab => tab.value === value);
    const next = STATUS_TABS[(index + step + STATUS_TABS.length) % STATUS_TABS.length];
    onChange(next.value);
  }
  return <div className={styles.tabs} role="tablist" aria-label="状态分段"
    onKeyDown={event => {
      if (event.key === 'ArrowRight') { event.preventDefault(); move(1); }
      if (event.key === 'ArrowLeft') { event.preventDefault(); move(-1); }
    }}>
    {STATUS_TABS.map(tab => <button key={tab.value} type="button" role="tab" id={`status-tab-${tab.value}`}
      aria-selected={value === tab.value} aria-controls={`status-panel-${tab.value}`} tabIndex={value === tab.value ? 0 : -1}
      onClick={() => onChange(tab.value)}>{tab.label}</button>)}
  </div>;
}
