import React, { useRef, useImperativeHandle, forwardRef } from 'react';
import { ChevronUp, ChevronDown } from 'lucide-react';
import styles from './TextInput.module.css';

export interface TextInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  invalid?: boolean;
  /** ghost 默认无边框，仅聚焦编辑时显示；outline 保持原有样式。 */
  appearance?: 'outline' | 'ghost';
}

/**
 * 统一风格的文本/数字输入框，与 Select(md)、Button 尺寸体系一致。
 * 当 type="number" 时，移除 WebKit 默认带灰色背景的 stepper，展示无背景纯 icon 微调控制。
 */
export const TextInput = forwardRef<HTMLInputElement, TextInputProps>(({
  className = '',
  invalid = false,
  appearance = 'outline',
  type,
  disabled,
  ...props
}, ref) => {
  const internalRef = useRef<HTMLInputElement>(null);
  const appearanceClass = appearance === 'ghost' ? styles.ghost : '';
  useImperativeHandle(ref, () => internalRef.current!);

  const handleStep = (direction: 'up' | 'down') => {
    const input = internalRef.current;
    if (!input || disabled) return;

    input.focus();
    const previousValue = input.value;
    try {
      if (direction === 'up') {
        input.stepUp();
      } else {
        input.stepDown();
      }
    } catch {
      const currentVal = Number(input.value) || 0;
      const step = Number(input.step) || 1;
      const nextVal = direction === 'up' ? currentVal + step : currentVal - step;
      input.value = String(nextVal);
    }

    const nativeSetter = Object.getOwnPropertyDescriptor(
      window.HTMLInputElement.prototype,
      'value'
    )?.set;
    if (nativeSetter) {
      const nextValue = input.value;
      // 恢复 React 跟踪值，再通过原生 setter 派发真实变更。
      input.value = previousValue;
      nativeSetter.call(input, nextValue);
    }

    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  };

  if (type === 'number') {
    return (
      <div className={styles.numberWrapper}>
        <input
          ref={internalRef}
          type="number"
          disabled={disabled}
          className={`${styles.input} ${styles.numberInput} ${appearanceClass} ${invalid ? styles.invalid : ''} ${className}`}
          {...props}
        />
        <div className={styles.stepper} aria-hidden="true">
          <button
            data-input-stepper
            type="button"
            tabIndex={-1}
            className={styles.stepBtn}
            onClick={() => handleStep('up')}
            onMouseDown={e => e.preventDefault()}
            disabled={disabled}
            aria-label="增加"
          >
            <ChevronUp size={11} strokeWidth={2.2} />
          </button>
          <button
            data-input-stepper
            type="button"
            tabIndex={-1}
            className={styles.stepBtn}
            onClick={() => handleStep('down')}
            onMouseDown={e => e.preventDefault()}
            disabled={disabled}
            aria-label="减少"
          >
            <ChevronDown size={11} strokeWidth={2.2} />
          </button>
        </div>
      </div>
    );
  }

  return (
    <input
      ref={internalRef}
      type={type}
      disabled={disabled}
      className={`${styles.input} ${appearanceClass} ${invalid ? styles.invalid : ''} ${className}`}
      {...props}
    />
  );
});

TextInput.displayName = 'TextInput';
