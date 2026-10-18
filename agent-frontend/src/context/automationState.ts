import { createContext, useContext } from 'react';
import type { AutomationSnapshot, ComputerActivity } from '../types/automation';

/** 任务状态由主窗口的单一 SSE 订阅提供，提醒窗口独立读取权威快照。 */
export interface AutomationState {
  snapshot: AutomationSnapshot;
  connected: boolean;
  activity: ComputerActivity | null;
  activitySyncError: string | null;
}
export const AutomationStateContext = createContext<AutomationState>({
  snapshot: { tasks: [], pending: [] }, connected: false, activity: null, activitySyncError: null,
});
export const useAutomations = () => useContext(AutomationStateContext);
