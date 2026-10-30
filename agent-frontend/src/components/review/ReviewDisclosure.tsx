import type { ReactNode } from 'react';
import styles from './ReviewDisclosure.module.css';

/** 次要复盘操作按需展开，保持主线阅读简洁。 */
export function ReviewDisclosure({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return <details className={styles.section}><summary>{title}{description && <span>{description}</span>}</summary>
    <div className={styles.content}>{children}</div></details>;
}
