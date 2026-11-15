import type { ReactNode } from 'react';
import styles from './StatusCard.module.css';

interface StatusCardProps {
  title: string;
  icon?: ReactNode;
  badge?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  tone?: 'default' | 'accent' | 'muted';
  variant?: 'card' | 'open';
  className?: string;
  children?: ReactNode;
}

/** 「状态」模块无边框分区容器：以留白与字号层级组织内容，不渲染外框卡片线。 */
export function StatusCard({ title, icon, badge, description, actions, tone = 'default', variant = 'open', className = '', children }: StatusCardProps) {
  return <section aria-label={title} className={`${styles.card} ${styles[tone]} ${variant === 'open' ? styles.open : ''} ${className}`}>
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
