import type { ReactNode } from 'react';
import { LoadingOverlay } from '../common/LoadingOverlay';
import { useSettingsLoading } from './settingsLoadingState';
import styles from './SettingsPageLayout.module.css';

interface SettingsPageLayoutProps {
  title: string;
  description: string;
  actions?: ReactNode;
  density?: 'default' | 'compact';
  /** 覆盖整个设置右侧主区域；侧栏保持可交互。 */
  loading?: boolean;
  loadingLabel?: string;
  children: ReactNode;
}

/** 设置详情页统一骨架：对齐模型配置页的内容宽度、标题层级和操作区。 */
export function SettingsPageLayout({
  title,
  description,
  actions,
  density = 'default',
  loading = false,
  loadingLabel = '正在读取设置…',
  children,
}: SettingsPageLayoutProps) {
  const hasMainRegion = useSettingsLoading(loading, loadingLabel);
  return (
    <section
      className={`${styles.page} ${density === 'compact' ? styles.compact : ''}`}
      aria-labelledby="settings-page-title"
    >
      <div className={styles.body} inert={loading} aria-busy={loading}>
        <header className={styles.header}>
          <div>
            <h1 id="settings-page-title">{title}</h1>
            <p>{description}</p>
          </div>
          {actions && <div className={styles.actions}>{actions}</div>}
        </header>
        <div className={styles.content}>{children}</div>
      </div>
      <LoadingOverlay active={loading && !hasMainRegion} label={loadingLabel} size="large" />
    </section>
  );
}
