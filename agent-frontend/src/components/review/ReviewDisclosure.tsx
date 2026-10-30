import type { ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import styles from './ReviewDisclosure.module.css';

/** 次要复盘操作按需展开，保持主线阅读简洁。 */
export function ReviewDisclosure({ title, description, children, variant = 'card' }: { title: string; description?: string; children: ReactNode; variant?: 'card' | 'inline' }) {
  return <details className={`${styles.section} ${variant === 'inline' ? styles.inline : ''}`}><summary className={styles.summary}>
    <ChevronRight className={styles.chevron} size={16} aria-hidden="true" />
    <span className={styles.copy}><span className={styles.title}>{title}</span>{description && <span className={styles.description}>{description}</span>}</span>
  </summary>
    <div className={styles.content}>{children}</div></details>;
}
