import { useEffect, useRef } from 'react';
import { LoadingTree, type LoadingTreeProps } from './LoadingTree';
import styles from './LoadingOverlay.module.css';

export interface LoadingOverlayProps {
  active: boolean;
  label?: string;
  size?: LoadingTreeProps['size'];
}

/** 局部毛玻璃等待层；父容器需定位，并将被遮挡内容设为 inert。 */
export function LoadingOverlay({ active, label = '正在处理，请稍候…', size = 'small' }: LoadingOverlayProps) {
  const overlay = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!active) return;
    const previous = document.activeElement;
    const node = overlay.current;
    node?.focus();
    return () => {
      // 不抢夺用户已转移到其他区域的焦点。
      if ((!document.activeElement || document.activeElement === document.body || document.activeElement === node)
        && previous instanceof HTMLElement && previous.isConnected && !previous.closest('[inert]')) previous.focus();
    };
  }, [active]);
  if (!active) return null;
  return <div ref={overlay} className={styles.overlay} tabIndex={-1} onMouseDown={event => event.stopPropagation()}>
    <LoadingTree size={size} label={label} />
  </div>;
}
