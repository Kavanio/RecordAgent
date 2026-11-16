import React, { useCallback, useEffect, useState, type ReactNode } from 'react';
import { readPreference, writePreference } from '../services/desktopPreferences';
import { SidebarLayoutContext } from './sidebarLayout';

const SIDEBAR_COLLAPSED_KEY = 'butvan.sidebarCollapsed';

function readInitialCollapsed(): boolean {
  try {
    return readPreference(SIDEBAR_COLLAPSED_KEY) === 'true';
  } catch {
    return false;
  }
}

export const SidebarLayoutProvider: React.FC<{ children: ReactNode; disabled?: boolean }> = ({
  children,
  disabled = false,
}) => {
  const [collapsed, setCollapsed] = useState<boolean>(readInitialCollapsed);

  const persist = useCallback((next: boolean) => {
    try {
      writePreference(SIDEBAR_COLLAPSED_KEY, String(next));
    } catch {
      // 忽略本地偏好写入异常
    }
  }, []);

  const collapse = useCallback(() => {
    setCollapsed(true);
    persist(true);
  }, [persist]);

  const expand = useCallback(() => {
    setCollapsed(false);
    persist(false);
  }, [persist]);

  const toggle = useCallback(() => {
    setCollapsed((prev) => {
      const next = !prev;
      persist(next);
      return next;
    });
  }, [persist]);

  useEffect(() => {
    const activeCollapsed = !disabled && collapsed;
    document.documentElement.dataset.sidebarCollapsed = activeCollapsed ? 'true' : 'false';
    return () => {
      delete document.documentElement.dataset.sidebarCollapsed;
    };
  }, [collapsed, disabled]);

  useEffect(() => {
    if (disabled) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.isComposing) return;
      if (!(event.metaKey || event.ctrlKey) || event.altKey || event.shiftKey || event.code !== 'KeyB') {
        return;
      }
      const target = event.target as HTMLElement | null;
      if (
        target
        && typeof target.closest === 'function'
        && (target.isContentEditable || target.closest('[contenteditable="true"]'))
      ) {
        return;
      }
      event.preventDefault();
      toggle();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [disabled, toggle]);

  return (
    <SidebarLayoutContext.Provider value={{ collapsed, canToggle: true, collapse, expand, toggle }}>
      {children}
    </SidebarLayoutContext.Provider>
  );
};
