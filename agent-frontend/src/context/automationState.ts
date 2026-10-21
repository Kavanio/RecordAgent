import { createContext, useContext } from 'react';
import type { AutomationSnapshot, AutomationSyncStatus, ComputerActivity } from '../types/automation';

/** 任务状态由主窗口的单一 SSE 订阅提供，提醒窗口独立读取权威快照。 */
export interface AutomationState {
  snapshot: AutomationSnapshot;
  connected: boolean;
  syncStatus: AutomationSyncStatus;
  lastSyncedAt: number | null;
  historyRevision: number;
  refreshSnapshot: () => Promise<AutomationSnapshot>;
  retrySync: () => void;
  activity: ComputerActivity | null;
  activitySyncError: string | null;
}
export const AutomationStateContext = createContext<AutomationState>({
  snapshot: { tasks: [], pending: [] }, connected: false, syncStatus: 'CONNECTING', lastSyncedAt: null, historyRevision: 0,
  refreshSnapshot: () => Promise.reject(new Error('任务状态尚未初始化')), retrySync: () => {},
  activity: null, activitySyncError: null,
});
export const useAutomations = () => useContext(AutomationStateContext);
