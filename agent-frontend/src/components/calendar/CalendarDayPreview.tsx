import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { XIcon } from '@phosphor-icons/react';
import { CalendarDayDetails } from './CalendarDayDetails';
import styles from './CalendarDayPreview.module.css';
interface CalendarDayPreviewProps {
  date: Date;
  id: string;
  anchor: HTMLElement;
  onEnter: () => void;
  onLeave: () => void;
  onClose: () => void;
}
/** 单个浮层容器，异步详情不改变定位方向。 */
export function CalendarDayPreview({
  date,
  id,
  anchor,
  onEnter,
  onLeave,
  onClose,
}: CalendarDayPreviewProps) {
  const panel = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: 0, top: 0 });
  useLayoutEffect(() => {
    const place = () => {
      const rect = anchor.getBoundingClientRect();
      // 按面板上限预留空间，异步内容加载后不翻转方向，避免悬停跳动。
      const height = Math.min(360, window.innerHeight - 24);
      const width = panel.current?.offsetWidth ?? 320;
      // 优先放在日期侧边，保留可直接移入的短间隙；内容变化不改变锚点。
      const right = rect.right + 6;
      const left = rect.left - width - 6;
      const beside = right + width <= window.innerWidth - 12 || left >= 12;
      setPosition({
        left: beside
          ? right + width <= window.innerWidth - 12
            ? right
            : left
          : Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)),
        top: beside
          ? Math.max(12, Math.min(rect.top, window.innerHeight - height - 12))
          : Math.max(
              12,
              Math.min(rect.bottom + 6, window.innerHeight - height - 12),
            ),
      });
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [anchor]);
  useEffect(() => {
    const dismiss = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', dismiss);
    return () => document.removeEventListener('keydown', dismiss);
  }, [onClose]);
  return createPortal(
    <div
      ref={panel}
      id={id}
      role="region"
      aria-label="每日详情"
      tabIndex={0}
      className={styles.preview}
      style={position}
      onMouseEnter={onEnter}
      onMouseLeave={onLeave}
      onFocus={onEnter}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) onLeave();
      }}
    >
      <header className={styles.heading}>
        <div className={styles.titleGroup}>
          <strong>
            {date.getMonth() + 1}月{date.getDate()}日
          </strong>
          <span>星期{'日一二三四五六'[date.getDay()]}</span>
        </div>
        <button type="button" className={styles.closeBtn} onClick={onClose} aria-label="关闭每日详情">
          <XIcon size={14} />
        </button>
      </header>
      <CalendarDayDetails date={date} compact />
    </div>,
    document.body,
  );
}
