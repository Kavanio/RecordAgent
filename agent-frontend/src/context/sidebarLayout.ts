import { createContext, useContext } from 'react';

export interface SidebarLayoutContextValue {
  collapsed: boolean;
  canToggle: boolean;
  collapse: () => void;
  expand: () => void;
  toggle: () => void;
}

export const DEFAULT_SIDEBAR_LAYOUT: SidebarLayoutContextValue = {
  collapsed: false,
  canToggle: false,
  collapse: () => {},
  expand: () => {},
  toggle: () => {},
};

export const SidebarLayoutContext = createContext<SidebarLayoutContextValue>(DEFAULT_SIDEBAR_LAYOUT);

export function useSidebarLayout(): SidebarLayoutContextValue {
  return useContext(SidebarLayoutContext);
}
