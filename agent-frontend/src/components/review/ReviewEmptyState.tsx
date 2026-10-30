import { Inbox } from 'lucide-react';
import styles from './ReviewEmptyState.module.css';

interface Props { title: string; description?: string }
/** 复盘分组内统一空状态：保留完整留白、文字层级与卡片内边距。 */
export function ReviewEmptyState({ title, description }: Props) {
  return <div className={styles.empty}>
    <span className={styles.icon} aria-hidden="true"><Inbox size={18} strokeWidth={1.5} /></span>
    <p className={styles.title}>{title}</p>
    {description && <p className={styles.description}>{description}</p>}
  </div>;
}
