import React, { useEffect, useId, useRef, useState } from 'react';
import { Check, Hand, ShieldCheck, ShieldAlert } from 'lucide-react';
import type { SessionPermissionMode } from '../../types/chat';
import styles from './PermissionModeSelector.module.css';

interface PermissionModeSelectorProps {
  value: SessionPermissionMode;
  onChange: (mode: SessionPermissionMode) => void;
  disabled?: boolean;
  isSaving?: boolean;
}

const OPTIONS: Array<{
  value: SessionPermissionMode;
  label: string;
  description: string;
  Icon: typeof Hand;
}> = [
  { value: 'ASK', label: '请求批准', description: '联网、编辑文件或运行命令前先询问', Icon: Hand },
  { value: 'AUTO_EDIT', label: '自动批准编辑', description: '读取、搜索和工作区编辑自动批准，风险操作仍询问', Icon: ShieldCheck },
  { value: 'FULL_ACCESS', label: '完全访问', description: '可访问网络和电脑文件，仍受硬性安全限制', Icon: ShieldAlert },
];

/** 会话级权限模式选择器。危险模式只负责展示，二次确认由页面控制器统一处理。 */
export const PermissionModeSelector: React.FC<PermissionModeSelectorProps> = ({
  value,
  onChange,
  disabled = false,
  isSaving = false,
}) => {
  const [open, setOpen] = useState(false);
  const [confirmingFullAccess, setConfirmingFullAccess] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const listboxId = useId();
  const active = OPTIONS.find((option) => option.value === value) ?? OPTIONS[0];
  const ActiveIcon = active.Icon;

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
        setConfirmingFullAccess(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        setConfirmingFullAccess(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  useEffect(() => {
    if (disabled) {
      setOpen(false);
      setConfirmingFullAccess(false);
    }
  }, [disabled]);

  const focusOption = (index: number) => optionRefs.current[index]?.focus();

  return (
    <div ref={rootRef} className={styles.root}>
      <button
        ref={triggerRef}
        type="button"
        className={`${styles.trigger} ${value === 'FULL_ACCESS' ? styles.triggerDanger : ''}`}
        onClick={() => {
          setOpen((current) => !current);
          setConfirmingFullAccess(false);
        }}
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listboxId : undefined}
        title={disabled ? '任务运行或等待确认时无法切换权限' : '切换会话权限模式'}
      >
        <ActiveIcon size={14} className={styles.triggerIcon} aria-hidden="true" />
        <span className={styles.triggerLabel}>{isSaving ? '保存中…' : active.label}</span>
      </button>

      {open && (
        <div id={listboxId} className={styles.menu} role="listbox" aria-label="会话权限模式">
          <div className={styles.menuIntro}>AI 如何执行操作？</div>
          {OPTIONS.map((option, index) => {
            const selected = option.value === value;
            const danger = option.value === 'FULL_ACCESS';
            const Icon = option.Icon;
            return (
              <button
                key={option.value}
                ref={(node) => { optionRefs.current[index] = node; }}
                type="button"
                role="option"
                aria-selected={selected}
                className={`${styles.option} ${selected ? styles.optionSelected : ''} ${danger ? styles.optionDanger : ''}`}
                onClick={() => {
                  if (danger && !selected) {
                    setConfirmingFullAccess(true);
                    return;
                  }
                  setOpen(false);
                  setConfirmingFullAccess(false);
                  if (!selected) onChange(option.value);
                  triggerRef.current?.focus();
                }}
                onKeyDown={(event) => {
                  if (event.key === 'ArrowDown') {
                    event.preventDefault();
                    focusOption((index + 1) % OPTIONS.length);
                  } else if (event.key === 'ArrowUp') {
                    event.preventDefault();
                    focusOption((index - 1 + OPTIONS.length) % OPTIONS.length);
                  } else if (event.key === 'Home') {
                    event.preventDefault();
                    focusOption(0);
                  } else if (event.key === 'End') {
                    event.preventDefault();
                    focusOption(OPTIONS.length - 1);
                  }
                }}
              >
                <Icon size={18} className={styles.optionIcon} aria-hidden="true" />
                <span className={styles.optionCopy}>
                  <span className={styles.optionLabel}>{option.label}</span>
                  <span className={styles.optionDescription}>{option.description}</span>
                </span>
                {selected && <Check size={16} className={styles.check} aria-hidden="true" />}
              </button>
            );
          })}
          {confirmingFullAccess && (
            <div className={styles.confirmation} role="alert">
              <div className={styles.confirmationTitle}>确认启用完全访问？</div>
              <div className={styles.confirmationDescription}>
                AI 将能访问互联网和电脑文件，并执行本机操作；系统硬性安全限制仍然有效。
              </div>
              <div className={styles.confirmationActions}>
                <button
                  type="button"
                  className={styles.cancelButton}
                  onClick={() => setConfirmingFullAccess(false)}
                >
                  取消
                </button>
                <button
                  type="button"
                  className={styles.confirmButton}
                  onClick={() => {
                    setOpen(false);
                    setConfirmingFullAccess(false);
                    onChange('FULL_ACCESS');
                    triggerRef.current?.focus();
                  }}
                >
                  确认启用
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
