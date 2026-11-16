import { useEffect, useRef, useState, type RefObject } from 'react';
import { readPreference, writePreference } from '../../services/desktopPreferences';

export const SIDEBAR_DEFAULT_WIDTH = 260;
const SIDEBAR_MIN_WIDTH = 220;
const SIDEBAR_MAX_WIDTH = 400;
const SIDEBAR_WIDTH_KEY = 'butvan.sidebarWidth';

/** 管理侧边栏拖拽宽度调整与本地偏好持久化。 */
export function useSidebarWidth(sidebarRef: RefObject<HTMLElement | null>) {
  const [sidebarWidth, setSidebarWidth] = useState<number>(() => {
    try {
      const saved = Number(readPreference(SIDEBAR_WIDTH_KEY));
      if (saved >= SIDEBAR_MIN_WIDTH && saved <= SIDEBAR_MAX_WIDTH) {
        return saved;
      }
    } catch {
      // 忽略本地存储异常，使用默认宽度
    }
    return SIDEBAR_DEFAULT_WIDTH;
  });
  const sidebarWidthRef = useRef(sidebarWidth);
  sidebarWidthRef.current = sidebarWidth;
  const [isResizing, setIsResizing] = useState(false);

  useEffect(() => {
    if (!isResizing) return;

    const onMouseMove = (e: MouseEvent) => {
      const rect = sidebarRef.current?.getBoundingClientRect();
      if (!rect) return;
      const next = Math.min(
        SIDEBAR_MAX_WIDTH,
        Math.max(SIDEBAR_MIN_WIDTH, Math.round(e.clientX - rect.left)),
      );
      setSidebarWidth(next);
    };

    const onMouseUp = () => {
      setIsResizing(false);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      try {
        writePreference(SIDEBAR_WIDTH_KEY, String(sidebarWidthRef.current));
      } catch {
        // 忽略本地存储异常
      }
    };

    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('mousemove', onMouseMove);
    window.addEventListener('mouseup', onMouseUp);
    return () => {
      window.removeEventListener('mousemove', onMouseMove);
      window.removeEventListener('mouseup', onMouseUp);
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };
  }, [isResizing, sidebarRef]);

  return {
    sidebarWidth,
    isResizing,
    startResizing: () => setIsResizing(true),
    resetWidth: () => setSidebarWidth(SIDEBAR_DEFAULT_WIDTH),
  };
}
