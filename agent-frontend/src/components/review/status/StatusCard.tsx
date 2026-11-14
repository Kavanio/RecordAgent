import type { ReactNode } from 'react';
import styles from './StatusCard.module.css';

interface StatusCardProps {
  title: string;
  icon?: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  tone?: 'default' | 'accent' | 'muted';
  className?: string;
  children?: ReactNode;
}

/** 「状态」模块统一卡片外壳：标题、说明与右上角操作，取代设置页的行式分组。 */
export function StatusCard({ title, icon, description, actions, tone = 'default', className = '', children }: StatusCardProps) {
  return <section aria-label={title} className={`${styles.card} ${styles[tone]} ${className}`}>
    <header className={styles.header}>
      <div className={styles.heading}>
        {icon && <span className={styles.icon} aria-hidden="true">{icon}</span>}
        <div className={styles.titles}>
          <h2>{title}</h2>
          {description && <p>{description}</p>}
        </div>
      </div>
      {actions && <div className={styles.actions}>{actions}</div>}
    </header>
    {children !== undefined && <div className={styles.body}>{children}</div>}
  </section>;
}
