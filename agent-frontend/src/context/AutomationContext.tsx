import { useEffect, useState, useRef, type ReactNode } from 'react';
import type { AutomationSnapshot, ComputerActivity } from '../types/automation';
import { AutomationStateContext } from './automationState';
import { subscribeAutomations, claimAutomation, automationReceipt, reportComputerActivity } from '../services/automationApi';
import { deliverTaskNotification, isTaskDesktop, listenTaskActivity, syncTaskReminder, taskActivityStatus, taskNotificationStatus } from '../services/taskDesktop';
import { useMessage } from '../components/common/Message';

/** 主窗口只订阅一次；辅助提醒窗口只读快照，不负责投递和采样。 */
export function AutomationProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<AutomationSnapshot>({ tasks: [], pending: [] });
  const [connected, setConnected] = useState(false);
  const [activity, setActivity] = useState<ComputerActivity | null>(null);
  const [activitySyncError, setActivitySyncError] = useState<string | null>(null);
  const activitySyncFailed = useRef(false);
  const inFlight = useRef(new Set<string>());
  const snapshotRef = useRef(snapshot); snapshotRef.current = snapshot;
  const { showMessage } = useMessage();
  const isReminder = new URLSearchParams(window.location.search).get('view') === 'task-reminder';
  const isStudyWidget = new URLSearchParams(window.location.search).get('view') === 'study-widget';
  useEffect(() => {
    if (isStudyWidget) return;
    return subscribeAutomations(value => { setSnapshot(value); setConnected(true); }, () => setConnected(false));
  }, [isStudyWidget]);
  useEffect(() => {
    if (isReminder || isStudyWidget) return;
    let stopped = false; let remove: (() => void) | undefined; let sending = false;
    void taskActivityStatus().then(value => { if (!stopped) setActivity(value); }).catch(() => { if (!stopped) setActivity({ supported:false,locked:false,idleSeconds:0 }); });
    void listenTaskActivity(sample => {
      if (stopped) return;
      setActivity(sample);
      if (!snapshotRef.current.tasks.some(t => t.status === 'ENABLED' && t.spec.kind === 'SEDENTARY')) {
        activitySyncFailed.current = false;
        setActivitySyncError(null);
        return;
      }
      if (sending) return;
      sending = true;
      void reportComputerActivity(sample).then(() => {
        activitySyncFailed.current = false;
        setActivitySyncError(null);
      }).catch(error => {
        const reason = error instanceof TypeError
          ? '网络或跨域请求失败'
          : error instanceof Error ? error.message : '未知错误';
        setActivitySyncError(reason);
        if (!activitySyncFailed.current) showMessage('error', `久坐使用时长同步失败：${reason}`);
        activitySyncFailed.current = true;
      }).finally(() => { sending = false; });
    }).then(unlisten => { if (stopped) unlisten(); else remove = unlisten; }).catch(() => setActivity({ supported:false,locked:false,idleSeconds:0 }));
    return () => { stopped = true; remove?.(); };
  }, [isReminder, isStudyWidget, showMessage]);
  useEffect(() => {
    if (isReminder || isStudyWidget || !connected) return;
    for (const run of snapshot.pending) {
      if (run.desktopStatus !== 'PENDING' || inFlight.current.has(run.id)) continue;
      inFlight.current.add(run.id);
      void (async () => {
        try {
          if (!await claimAutomation(run.id)) return;
          let status = 'FAILED';
          try {
            status = isTaskDesktop() && run.confirmation === 'WAITING'
              ? 'SUBMITTED' : await deliverTaskNotification(run);
            if (!isTaskDesktop() && run.confirmation !== 'WAITING') showMessage('info', `${run.title}：${run.content.slice(0,100)}`);
            if (isTaskDesktop() && run.confirmation !== 'WAITING') {
              const notification = await taskNotificationStatus();
              if (notification.mode === 'DEV_FALLBACK') showMessage('info', `${run.title}：${run.content.slice(0,100)}`);
            }
          } catch (e) { showMessage('error', e instanceof Error ? e.message : '桌面通知失败，请查看任务历史'); }
          await automationReceipt(run.id, status);
        } catch { setConnected(false); }
        finally { inFlight.current.delete(run.id); }
      })();
    }
    if (isTaskDesktop()) void syncTaskReminder(snapshot.pending.some(run => run.confirmation === 'WAITING'))
      .catch(() => showMessage('error', '任务提醒窗口无法同步，请检查任务执行历史'));
  }, [snapshot, connected, isReminder, isStudyWidget, showMessage]);
  return <AutomationStateContext.Provider value={{ snapshot, connected, activity, activitySyncError }}>{children}</AutomationStateContext.Provider>;
}
