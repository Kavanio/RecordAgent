import type { ReactNode } from 'react';
import { Card } from '../common/Card';
import styles from './FinanceChartCard.module.css';

interface FinanceChartCardProps {
  title: string;
  subtitle?: string;
  actions?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
  label?: string;
}

/** 财务分析图表的统一卡片，集中维护标题、操作区、留白与统计口径。 */
export function FinanceChartCard({ title, subtitle, actions, footer, children, label }: FinanceChartCardProps) {
  return <Card className={styles.card} role="region" aria-label={label ?? title}>
    <div className={styles.heading}>
      <div className={styles.title}><h3>{title}</h3>{subtitle && <p>{subtitle}</p>}</div>
      {actions}
    </div>
    <div className={styles.body}>{children}</div>
    {footer && <div className={styles.footer}>{footer}</div>}
  </Card>;
}
