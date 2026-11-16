import React from 'react';
import { PanelLeft } from 'lucide-react';
import { useSidebarLayout } from '../../context/sidebarLayout';
import styles from './SidebarToggleButton.module.css';

/** 当左侧边栏已收起时，在主工作区顶栏左侧渲染展开入口。 */
export const SidebarExpandButton: React.FC = () => {
  const { collapsed, canToggle, expand } = useSidebarLayout();
  if (!canToggle || !collapsed) return null;

  return (
    <button
      type="button"
      className={styles.expandBtn}
      onClick={expand}
      aria-expanded={false}
      aria-label="展开侧边栏"
      aria-keyshortcuts="Meta+B Control+B"
      title="展开侧边栏（⌘B）"
    >
      <PanelLeft size={16} strokeWidth={1.7} aria-hidden="true" />
    </button>
  );
};
