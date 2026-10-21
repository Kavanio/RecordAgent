import { useCallback, useState, type ReactNode } from 'react';
import { LoadingOverlay } from '../common/LoadingOverlay';
import { SettingsLoadingContext } from './settingsLoadingState';
import styles from './SettingsMainRegion.module.css';

interface SettingsMainRegionProps {
  children: ReactNode;
  contentClassName?: string;
}

/** 设置右侧的固定可视区域；滚动内容与遮罩分层，侧栏不参与遮挡或 inert。 */
export function SettingsMainRegion({ children, contentClassName = '' }: SettingsMainRegionProps) {
  const [pending, setPending] = useState<ReadonlyMap<symbol, string>>(new Map());
  const report = useCallback((source: symbol, label: string | null) => {
    setPending(current => {
      if (label === null ? !current.has(source) : current.get(source) === label) return current;
      const next = new Map(current);
      if (label === null) next.delete(source);
      else next.set(source, label);
      return next;
    });
  }, []);
  const loading = pending.size > 0;
  const label = Array.from(pending.values()).at(-1);
  return <SettingsLoadingContext.Provider value={report}>
    <section className={styles.region} aria-label="设置主区域">
      <div className={`${styles.content} ${contentClassName}`} inert={loading} aria-busy={loading}>{children}</div>
      <LoadingOverlay active={loading} label={label} size="large" />
    </section>
  </SettingsLoadingContext.Provider>;
}
