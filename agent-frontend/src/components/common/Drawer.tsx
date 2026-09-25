import React, { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import styles from './Drawer.module.css';

export interface DrawerProps {
  open: boolean;
  title: string;
  description?: string;
  onClose: () => void;
  children: React.ReactNode;
  width?: number | string;
  variant?: 'default' | 'minimal';
}

const FOCUSABLE = 'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])';

/** 通用右侧抽屉：提供遮罩关闭、焦点管理和键盘访问能力。 */
export const Drawer: React.FC<DrawerProps> = ({
  open, title, description, onClose, children, width = 560, variant = 'default',
}) => {
  const panelRef = useRef<HTMLElement>(null);
  const triggerRef = useRef<HTMLElement | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    triggerRef.current = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    panel?.focus();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !panel) return;
      const focusables = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE))
        .filter((element) => !element.hasAttribute('disabled'));
      if (!focusables.length) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      triggerRef.current?.focus();
    };
  }, [open]);

  if (!open) return null;

  return createPortal(
    <div className={styles.overlay} onMouseDown={onClose}>
      <aside
        ref={panelRef}
        className={`${styles.panel} ${variant === 'minimal' ? styles.minimal : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="drawer-title"
        aria-describedby={description ? 'drawer-description' : undefined}
        tabIndex={-1}
        style={{ width }}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className={styles.header}>
          <div>
            <h2 id="drawer-title">{title}</h2>
            {description && <p id="drawer-description">{description}</p>}
          </div>
          <button type="button" className={styles.closeButton} onClick={onClose} aria-label="关闭抽屉" title="关闭 (Esc)">
            <X size={17} />
          </button>
        </header>
        <div className={styles.body}>{children}</div>
      </aside>
    </div>,
    document.body,
  );
};
