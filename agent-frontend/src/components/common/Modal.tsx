import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { LoadingOverlay } from './LoadingOverlay';
import styles from './Modal.module.css';

export interface ModalProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: React.ReactNode;
  /** 弹框宽度，默认 520px */
  width?: number | string;
  /** 是否在应用窗口中垂直居中；默认保持顶部对齐 */
  centered?: boolean;
  className?: string;
  /** 提交期间阻止关闭与底层交互，复用局部加载遮罩。 */
  busy?: boolean;
  busyLabel?: string;
  /** 标题栏图标（可选） */
  icon?: React.ReactNode;
}


const FOCUSABLE =
  'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';
let nextModalId = 0;
const modalStack: number[] = [];

/**
 * 通用模态弹框：遮罩点击 / Esc 关闭、焦点陷阱、aria 语义。
 */
export const Modal: React.FC<ModalProps> = ({
  open,
  title,
  onClose,
  children,
  width = 520,
  centered = false,
  className = '',
  icon,
  busy = false,
  busyLabel,
}) => {

  const panelRef = useRef<HTMLDivElement>(null);
  const modalIdRef = useRef(0);
  if (modalIdRef.current === 0) modalIdRef.current = ++nextModalId;
  const onCloseRef = useRef(onClose);
  onCloseRef.current = () => { if (!busy) onClose(); };

  useEffect(() => {
    if (!open) return;

    const modalId = modalIdRef.current;
    const previousFocus = document.activeElement;
    modalStack.push(modalId);
    const panel = panelRef.current;
    const autoFocusTarget = panel?.querySelector<HTMLElement>('[autofocus]');
    if (autoFocusTarget) autoFocusTarget.focus();
    else if (panel) panel.focus();

    const handleKeyDown = (e: KeyboardEvent) => {
      if (modalStack.at(-1) !== modalId) return;
      if (e.key === 'Escape') {
        onCloseRef.current();
        return;
      }
      if (e.key !== 'Tab' || !panel) return;

      const focusables = Array.from(
        panel.querySelectorAll<HTMLElement>(FOCUSABLE)
      ).filter((el) => !el.hasAttribute('disabled') && !el.closest('[inert]'));
      if (focusables.length === 0) {
        e.preventDefault();
        panel.focus();
        return;
      }

      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      const stackIndex = modalStack.lastIndexOf(modalId);
      if (stackIndex >= 0) modalStack.splice(stackIndex, 1);
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, [open]);

  if (!open) return null;

  return createPortal(
    <div
      className={`${styles.overlay} ${centered ? styles.overlayCentered : ''}`}
      onMouseDown={() => onCloseRef.current()}
    >
      <div
        ref={panelRef}
        className={`${styles.panel} ${className}`}
        role="dialog"
        aria-modal="true"
        aria-busy={busy}
        aria-label={title}
        tabIndex={-1}
        style={{ width }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className={styles.header} inert={busy}>
          <div className={styles.titleWrapper}>
            {icon && <span className={styles.headerIcon}>{icon}</span>}
            <h3 className={styles.title}>{title}</h3>
          </div>
          <button
            type="button"
            className={styles.closeBtn}
            onClick={() => onCloseRef.current()}
            aria-label="关闭"
            title="关闭 (Esc)"
          >
            <X size={16} />
          </button>
        </div>

        <div className={styles.body} inert={busy}>{children}</div>
        <LoadingOverlay active={busy} label={busyLabel} />
      </div>
    </div>,
    document.body,
  );
};
