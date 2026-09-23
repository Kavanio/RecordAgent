import { useEffect, useState, useRef, type ReactNode } from 'react';
import type { AutomationSnapshot, ComputerActivity } from '../types/automation';
import { AutomationStateContext } from './automationState';
import { subscribeAutomations, claimAutomation, automationReceipt, reportComputerActivity } from '../services/automationApi';
import { deliverTaskNotification, isTaskDesktop, listenTaskActivity, taskActivityStatus } from '../services/taskDesktop';
import { useMessage } from '../components/common/Message';

/** 主窗口只订阅一次；辅助提醒窗口只读快照，不负责投递和采样。 */
export function AutomationProvider({ children }: { children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<AutomationSnapshot>({ tasks: [], pending: [] });
  const [connected, setConnected] = useState(false);
  const [activity, setActivity] = useState<ComputerActivity | null>(null);
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
      if (sending || !snapshotRef.current.tasks.some(t => t.status === 'ENABLED' && t.spec.kind === 'SEDENTARY')) return;
      sending = true;
      void reportComputerActivity(sample).catch(() => setConnected(false)).finally(() => { sending = false; });
    }).then(unlisten => { if (stopped) unlisten(); else remove = unlisten; }).catch(() => setActivity({ supported:false,locked:false,idleSeconds:0 }));
    return () => { stopped = true; remove?.(); };
  }, [isReminder, isStudyWidget]);
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
            status = await deliverTaskNotification(run);
            if (!isTaskDesktop() && run.confirmation !== 'WAITING') showMessage('info', `${run.title}：${run.content.slice(0,100)}`);
          } catch (e) { showMessage('error', e instanceof Error ? e.message : '桌面通知失败，请查看任务历史'); }
          await automationReceipt(run.id, status);
        } catch { setConnected(false); }
        finally { inFlight.current.delete(run.id); }
      })();
    }
    // 原生通知中心展示提醒，待确认状态保留在任务页，不自动打开置顶窗口。
  }, [snapshot, connected, isReminder, isStudyWidget, showMessage]);
  return <AutomationStateContext.Provider value={{ snapshot, connected, activity }}>{children}</AutomationStateContext.Provider>;
}
