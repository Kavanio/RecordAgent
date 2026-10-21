import React from 'react';
import { AlertTriangle, AlertCircle, Info } from 'lucide-react';
import { Modal } from './Modal';
import { Button } from './Button';
import styles from './ConfirmDialog.module.css';

export interface ConfirmDialogProps {
  /** 弹框显隐 */
  open: boolean;
  /** 弹框标题 */
  title: string;
  /** 语气/类型：'warning'（默认，橙黄预警）| 'danger'（红色危险操作）| 'info'（蓝色通知） */
  tone?: 'warning' | 'danger' | 'info';
  /** 自定义标题栏图标（未提供则按 tone 自动展示） */
  icon?: React.ReactNode;
  /** 描述文案（简单提示直接使用该属性） */
  description?: React.ReactNode;
  /** 复杂主体内容（若有结构化内容直接传入 children） */
  children?: React.ReactNode;
  /** 取消按钮文字，默认 '取消' */
  cancelText?: string;
  /** 确认按钮文字，默认 '确认' */
  confirmText?: string;
  /** 确认按钮变体：默认 tone==='danger' 时为 'danger'，其余为 'primary' */
  confirmVariant?: 'primary' | 'danger' | 'outline';
  /** 是否展示取消按钮，默认 true */
  showCancel?: boolean;
  /** 是否处于处理中/禁用状态 */
  busy?: boolean;
  /** 弹框宽度，默认 420px */
  width?: number | string;
  /** 取消/关闭回调 */
  onClose: () => void;
  /** 确认回调 */
  onConfirm: () => void | Promise<void>;
}

export const ConfirmDialog: React.FC<ConfirmDialogProps> = ({
  open,
  title,
  tone = 'warning',
  icon,
  description,
  children,
  cancelText = '取消',
  confirmText = '确认',
  confirmVariant,
  showCancel = true,
  busy = false,
  width = 420,
  onClose,
  onConfirm,
}) => {
  if (!open) return null;

  const defaultIcon =
    tone === 'danger' ? (
      <AlertCircle size={16} strokeWidth={2.2} />
    ) : tone === 'info' ? (
      <Info size={16} strokeWidth={2.2} />
    ) : (
      <AlertTriangle size={16} strokeWidth={2.2} />
    );

  const headerIcon = (
    <div className={`${styles.iconBadge} ${styles[tone]}`}>
      {icon ?? defaultIcon}
    </div>
  );

  const resolvedConfirmVariant =
    confirmVariant ?? (tone === 'danger' ? 'danger' : 'primary');

  return (
    <Modal
      open={open}
      title={title}
      icon={headerIcon}
      width={width}
      centered
      busy={busy}
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <div className={styles.container}>
        <div className={styles.body}>
          {description && <div className={styles.description}>{description}</div>}
          {children}
        </div>
        <div className={styles.actions}>
          {showCancel && (
            <Button
              type="button"
              variant="outline"
              size="md"
              disabled={busy}
              onClick={onClose}
            >
              {cancelText}
            </Button>
          )}
          <Button
            type="button"
            variant={resolvedConfirmVariant}
            size="md"
            disabled={busy}
            onClick={onConfirm}
          >
            {busy ? '处理中…' : confirmText}
          </Button>
        </div>
      </div>
    </Modal>
  );
};
