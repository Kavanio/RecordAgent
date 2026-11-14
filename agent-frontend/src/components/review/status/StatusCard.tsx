import type { ReactNode } from 'react';
import styles from './StatusCard.module.css';

interface StatusCardProps {
  title: string;
  icon?: ReactNode;
  badge?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  tone?: 'default' | 'accent' | 'muted';
  className?: string;
  children?: ReactNode;
}

/** 「状态」模块统一卡片外壳：标题、轻量状态标、说明与右上角操作。 */
export function StatusCard({ title, icon, badge, description, actions, tone = 'default', className = '', children }: StatusCardProps) {
  return <section aria-label={title} className={`${styles.card} ${styles[tone]} ${className}`}>
    <header className={styles.header}>
      <div className={styles.heading}>
        {icon && <span className={styles.icon} aria-hidden="true">{icon}</span>}
        <div className={styles.titles}>
          <div className={styles.titleRow}>
            <h2>{title}</h2>
            {badge}
          </div>
          {description && <p>{description}</p>}
        </div>
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </header>
    {children !== undefined && <div className={styles.body}>{children}</div>}
  </section>;
}
