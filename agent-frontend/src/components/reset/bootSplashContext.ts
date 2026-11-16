import { createContext, useContext, useLayoutEffect } from 'react';

export interface BootSplashContextValue {
  /** 登记或释放某个启动阶段对全屏加载动画的持有；所有阶段释放后淡出移除遮罩。 */
  setHold: (id: string, active: boolean) => void;
}

export const BootSplashContext = createContext<BootSplashContextValue | null>(null);

/** 当前是否处于外层统一启动加载遮罩下。 */
export function useHasBootSplash(): boolean {
  return useContext(BootSplashContext) !== null;
}

/** 在给定条件为 true 期间持续保留外层统一启动加载动画，避免跨阶段切换时动画重播。 */
export function useBootSplashHold(id: string, active: boolean): boolean {
  const splash = useContext(BootSplashContext);
  useLayoutEffect(() => {
    if (!splash) return;
    splash.setHold(id, active);
    return () => splash.setHold(id, false);
  }, [splash, id, active]);
  return splash !== null;
}
