import type { ReactNode } from 'react';
import { Modal } from '../common/Modal';
import styles from './ReviewFormModal.module.css';

interface Props { open: boolean; title: string; busy?: boolean; onClose: () => void; children: ReactNode; footer?: ReactNode }
/** 复盘领域统一编辑弹窗，约束字号、字段宽度、长文换行和滚动边界。 */
export function ReviewFormModal({ children, ...props }: Props) {
  return <Modal {...props} width={600} centered className={styles.panel} bodyClassName={styles.scrollArea}><div className={styles.body}>{children}</div></Modal>;
}
