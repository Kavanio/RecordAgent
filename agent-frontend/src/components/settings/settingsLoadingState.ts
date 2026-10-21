import { createContext, useContext, useLayoutEffect, useRef } from 'react';

/** 每个设置页独立登记等待状态，卸载旧页不能清除新页的加载。 */
export type SettingsLoadingReporter = (source: symbol, label: string | null) => void;
export const SettingsLoadingContext = createContext<SettingsLoadingReporter | null>(null);

/** 向设置主区域登记加载；无宿主时由页面布局使用局部降级遮罩。 */
export function useSettingsLoading(active: boolean, label: string): boolean {
  const report = useContext(SettingsLoadingContext);
  const source = useRef(Symbol('settings-loading'));
  useLayoutEffect(() => {
    if (!report || !active) return;
    const id = source.current;
    report(id, label);
    return () => report(id, null);
  }, [report, active, label]);
  return report !== null;
}
