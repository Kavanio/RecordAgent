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

/** 久坐提醒先停止提示音并开始休息；休息完成后确认并播放结束提示音，窗口随权威快照关闭。 */
export function TaskReminderWindow() {
  const { snapshot, connected } = useAutomations();
  const [now, setNow] = useState(Date.now());
  const [started, setStarted] = useState<Record<string, number>>({});
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
    } catch (e) {
      confirmFailed.current.add(id);
      setError(e instanceof Error ? e.message : '确认失败，请重试');
    } finally {
      finishing.current.delete(id); setBusy(null);
    }
  }, [pending, snapshot.tasks, preferences]);

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
  }

  const task = snapshot.tasks.find(value => value.id === active?.taskId);
  const sedentary = task?.spec.kind === 'SEDENTARY';
  const start = active ? started[active.id] || readStarted(active.id) : null;
  const remaining = start && sedentary ? Math.max(0, Math.ceil((start + task.spec.breakMinutes * 60_000 - now) / 1000)) : null;
  const time = remaining === null ? '' : `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
  const progress = remaining !== null && task ? Math.min(100, Math.max(0, (1 - remaining / (task.spec.breakMinutes * 60)) * 100)) : 0;

  const resting = remaining !== null && remaining > 0;
  useEffect(() => {
    const until = resting && connected && preferences?.restLock && start && sedentary
      ? start + task.spec.breakMinutes * 60_000 : null;
    const sync = () => {
      void syncTaskRestLock(until ? Math.max(0, (until - Date.now()) / 1000) : 0)
        .catch(e => setError(`休息遮罩无法同步：${String(e)}`));
    };
    sync();
    const timer = until ? window.setInterval(sync, 1000) : undefined;
    return () => {
      window.clearInterval(timer);
      void syncTaskRestLock(0).catch(e => console.warn('休息遮罩清理失败', e));
    };
  }, [resting, connected, preferences?.restLock, start, sedentary, task?.spec.breakMinutes]);

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
