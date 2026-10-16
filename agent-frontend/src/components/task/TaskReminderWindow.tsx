import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Bell } from 'lucide-react';
import { useAutomations } from '../../context/automationState';
import { confirmAutomation } from '../../services/automationApi';
import { openTaskMain } from '../../services/taskDesktop';
import { getTaskReminderConfiguration, syncTaskRestLock } from '../../services/taskReminderPreferences';
import { playReminderAudio, repeatReminderAudio } from '../../services/taskReminderAudio';
import type { TaskReminderPreferences } from '../../types/taskReminder';
import { Button } from '../common/Button';
import styles from './TaskReminderWindow.module.css';

const startedKey = (id: string) => `task-reminder-rest:${id}`;
const readStarted = (id: string) => Number(localStorage.getItem(startedKey(id))) || null;
interface RestLockSnapshot { runId: string; startedAt: number; durationMs: number; restLock: boolean }
function readRestLockSnapshot(): RestLockSnapshot | null {
  try {
    const value = JSON.parse(localStorage.getItem('butvan-task-rest-data') || 'null') as Partial<RestLockSnapshot> | null;
    return value && typeof value.runId === 'string' && Number.isFinite(value.startedAt)
      && Number.isFinite(value.durationMs) && value.durationMs! > 0
      ? { runId: value.runId, startedAt: value.startedAt!, durationMs: value.durationMs!, restLock: value.restLock === true }
      : null;
  } catch { return null; }
}

/** 久坐提醒先停止提示音并开始休息；休息完成后确认并播放结束提示音，窗口随权威快照关闭。 */
export function TaskReminderWindow() {
  const { snapshot, connected } = useAutomations();
  const [now, setNow] = useState(Date.now());
  const [started, setStarted] = useState<Record<string, number>>({});
  const [restLockSnapshot, setRestLockSnapshot] = useState(readRestLockSnapshot);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [reminderConfig, setReminderConfig] = useState<{ runId: string; preferences: TaskReminderPreferences } | null>(null);
  const [reloadConfig, setReloadConfig] = useState(0);
  const ringingController = useRef<AbortController | null>(null);
  const endingController = useRef<AbortController | null>(null);
  const confirmFailed = useRef(new Set<string>());
  const finishing = useRef(new Set<string>());
  const completed = useRef(new Set<string>());
  const pending = useMemo(() => snapshot.pending.filter(run => run.confirmation === 'WAITING'), [snapshot.pending]);
  const active = pending[0];
  const activeId = active?.id;
  const preferences = reminderConfig?.runId === active?.id ? reminderConfig?.preferences : undefined;
  const ringing = Boolean(active?.sound && !busy && !started[active.id] && !readStarted(active.id));

  useEffect(() => {
    if (!activeId) return;
    let cancelled = false;
    void getTaskReminderConfiguration().then(config => {
      if (!cancelled) setReminderConfig({ runId: activeId, preferences: config.preferences });
    }).catch(e => { if (!cancelled) setError(`提醒设置读取失败：${String(e)}`); });
    return () => { cancelled = true; };
  }, [activeId, reloadConfig]);
  useEffect(() => {
    const stop = () => {
      ringingController.current?.abort();
      endingController.current?.abort();
      void syncTaskRestLock(0).catch(e => console.warn('提醒窗口退出时清理遮罩失败', e));
    };
    window.addEventListener('pagehide', stop);
    return () => { window.removeEventListener('pagehide', stop); stop(); };
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!ringing || !connected || !preferences) return;
    const controller = new AbortController();
    ringingController.current = controller;
    void repeatReminderAudio(preferences.soundId, preferences.repeatSeconds, controller.signal)
      .catch(e => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e)); });
    return () => { controller.abort(); };
  }, [active?.id, ringing, connected, preferences]);

  const finish = useCallback(async (id: string) => {
    if (finishing.current.has(id) || completed.current.has(id)) return;
    finishing.current.add(id); setBusy(id); setError('');
    try {
      await confirmAutomation(id);
      const run = pending.find(value => value.id === id);
      const sedentary = snapshot.tasks.some(value => value.id === run?.taskId && value.spec.kind === 'SEDENTARY');
      if (sedentary && run?.sound) {
        try {
          const controller = new AbortController(); endingController.current = controller;
          await playReminderAudio(preferences?.soundId ?? 'builtin:bell', controller.signal);
        }
        catch (e) { console.warn('休息结束提示音播放失败', e); }
      }
      completed.current.add(id);
      localStorage.removeItem(startedKey(id));
      localStorage.removeItem('butvan-task-rest-data');
      setRestLockSnapshot(null);
      try {
        const bc = new BroadcastChannel('butvan-task-rest-sync');
        bc.postMessage({ type: 'FINISH', runId: id });
        bc.close();
      } catch {}
    } catch (e) {
      confirmFailed.current.add(id);
      setError(e instanceof Error ? e.message : '确认失败，请重试');
    } finally {
      finishing.current.delete(id); setBusy(null);
    }
  }, [pending, snapshot.tasks, preferences]);

  useEffect(() => {
    let bc: BroadcastChannel | null = null;
    try {
      bc = new BroadcastChannel('butvan-task-rest-sync');
      bc.onmessage = (event) => {
        if (event.data?.type === 'EARLY_FINISH' && event.data?.runId) {
          void finish(event.data.runId);
        }
      };
    } catch {}
    return () => {
      bc?.close();
    };
  }, [finish]);

  useEffect(() => {
    if (!connected) return;
    for (const run of pending) {
      const task = snapshot.tasks.find(value => value.id === run.taskId);
      if (task?.spec.kind !== 'SEDENTARY') continue;
      const start = started[run.id] || readStarted(run.id);
      if (start && now >= start + task.spec.breakMinutes * 60_000 && !confirmFailed.current.has(run.id) && !completed.current.has(run.id)) void finish(run.id);
    }
  }, [pending, snapshot.tasks, started, now, error, finish, connected]);

  function acknowledge(id: string, sedentary: boolean) {
    ringingController.current?.abort();
    if (!sedentary) { void finish(id); return; }
    const at = Date.now();
    localStorage.setItem(startedKey(id), String(at));
    setStarted(value => ({ ...value, [id]: at }));
    setNow(at);
    const currentTask = snapshot.tasks.find(value => value.id === active?.taskId);
    const restPayload = {
      runId: id,
      startedAt: at,
      durationMs: (currentTask?.spec?.breakMinutes ?? 5) * 60_000,
      breakMinutes: currentTask?.spec?.breakMinutes ?? 5,
      restLock: preferences?.restLock === true,
      title: active?.title || '久坐提醒',
      content: active?.content || '起来活动一下，深呼吸，眺望远方',
    };
    localStorage.setItem('butvan-task-rest-data', JSON.stringify(restPayload));
    setRestLockSnapshot({ runId: id, startedAt: at, durationMs: restPayload.durationMs, restLock: restPayload.restLock });
    try {
      const bc = new BroadcastChannel('butvan-task-rest-sync');
      bc.postMessage({ type: 'START', payload: restPayload });
      bc.close();
    } catch {}
  }

  const task = snapshot.tasks.find(value => value.id === active?.taskId);
  const sedentary = task?.spec.kind === 'SEDENTARY';
  const start = active ? started[active.id] || readStarted(active.id) : null;
  const remaining = start && sedentary ? Math.max(0, Math.ceil((start + task.spec.breakMinutes * 60_000 - now) / 1000)) : null;
  const time = remaining === null ? '' : `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
  const progress = remaining !== null && task ? Math.min(100, Math.max(0, (1 - remaining / (task.spec.breakMinutes * 60)) * 100)) : 0;

  const resting = remaining !== null && remaining > 0;
  useEffect(() => {
    if (!active || !sedentary || !start) return;
    const restPayload = {
      runId: active.id,
      startedAt: start,
      durationMs: (task?.spec?.breakMinutes ?? 5) * 60_000,
      breakMinutes: task?.spec?.breakMinutes ?? 5,
      restLock: preferences?.restLock ?? restLockSnapshot?.restLock ?? false,
      title: active.title || '久坐提醒',
      content: active.content || '起来活动一下，深呼吸，眺望远方',
    };
    localStorage.setItem('butvan-task-rest-data', JSON.stringify(restPayload));
  }, [active, sedentary, start, task?.spec?.breakMinutes, preferences?.restLock, restLockSnapshot?.restLock]);
  const lockUntil = (() => {
    const savedRest = !active || restLockSnapshot?.runId === active.id ? restLockSnapshot : null;
    const lockEnabled = active ? (preferences?.restLock ?? savedRest?.restLock ?? false) : savedRest?.restLock === true;
    const restStart = active ? (start ?? savedRest?.startedAt) : savedRest?.startedAt;
    const duration = active && sedentary ? task.spec.breakMinutes * 60_000 : savedRest?.durationMs;
    if (!lockEnabled || !restStart || !duration || (active && !sedentary)) return null;
    const until = restStart + duration;
    return until > now ? until : null;
  })();
  useEffect(() => {
    if (!lockUntil || !connected) return;
    const sync = () => {
      void syncTaskRestLock(Math.max(0, (lockUntil - Date.now()) / 1000))
        .catch(e => setError(`休息遮罩无法同步：${String(e)}`));
    };
    sync();
    const timer = window.setInterval(sync, 1000);
    return () => window.clearInterval(timer);
  }, [lockUntil, connected]);
  useEffect(() => {
    if (lockUntil) return;
    void syncTaskRestLock(0).catch(e => console.warn('休息遮罩清理失败', e));
  }, [lockUntil]);

  return <main className={styles.window}>
    <header className={styles.header}>
      <Bell className={styles.mark} size={18} strokeWidth={1.9} aria-hidden="true" />
      <span className={styles.appName}>任务提醒</span>
      <span className={styles.status}>{remaining !== null ? '休息中' : '等待确认'}</span>
    </header>
    {active ? <section className={styles.content} aria-label={active.title}>
      <div className={styles.copy}>
        <span className={styles.overline}>{sedentary ? '久坐提醒' : '定时提醒'}{pending.length > 1 ? ` · 另有 ${pending.length - 1} 条` : ''}</span>
        <h1>{sedentary ? '起来活动一下' : active.title}</h1>
        <p className={styles.description}>{active.content}</p>
      </div>
      {remaining !== null ? <div className={styles.timerBlock}>
        <div className={styles.time} role="timer" aria-label="剩余休息时间">{time}</div>
        <div className={styles.progress} aria-hidden="true"><span style={{ width: `${progress}%` }} /></div>
        <p className={styles.timerHint}>{remaining ? '休息结束后自动关闭' : '休息完成，正在确认…'}</p>
      </div> : <p className={styles.prompt}>{sedentary ? `确认后开始 ${task.spec.breakMinutes} 分钟休息，提示音将停止` : '确认后将关闭此提醒'}</p>}
    </section> : <div className={styles.empty} role="status">{connected ? '当前没有待处理的提醒' : '正在连接任务服务…'}</div>}
    <footer className={styles.footer}>
      {error && <p role="alert" className={styles.error}>{error}<button type="button" onClick={() => { if (active) confirmFailed.current.delete(active.id); setError(''); setReloadConfig(value => value + 1); }}>重试</button></p>}
      {active && remaining === null && <Button className={styles.primaryAction} variant="primary" disabled={Boolean(busy) || !connected || !preferences} onClick={() => acknowledge(active.id, sedentary)}>{busy === active.id ? '处理中…' : sedentary ? '确认并开始休息' : '确认提醒'}</Button>}
      <Button disabled={Boolean(remaining && preferences?.restLock)} className={styles.manageAction} variant="ghost" onClick={() => void openTaskMain().catch(() => setError('无法打开主窗口'))}>打开主窗口管理任务</Button>
    </footer>
  </main>;
}
