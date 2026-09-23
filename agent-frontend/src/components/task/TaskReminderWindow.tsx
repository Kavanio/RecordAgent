import { useEffect, useMemo, useRef, useState } from 'react';
import { useAutomations } from '../../context/automationState';
import { confirmAutomation } from '../../services/automationApi';
import { openTaskMain, playTaskReminderSound, stopTaskReminderSound } from '../../services/taskDesktop';
import { Button } from '../common/Button';
import styles from './TaskReminderWindow.module.css';

const startedKey = (id: string) => `task-reminder-rest:${id}`;
const readStarted = (id: string) => Number(localStorage.getItem(startedKey(id))) || null;

/** 久坐提醒先停止提示音并开始休息；休息完成后才通知后端确认，窗口随权威快照关闭。 */
export function TaskReminderWindow() {
  const { snapshot, connected } = useAutomations();
  const [now, setNow] = useState(Date.now());
  const [started, setStarted] = useState<Record<string, number>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');
  const finishing = useRef(new Set<string>());
  const completed = useRef(new Set<string>());
  const pending = useMemo(() => snapshot.pending.filter(run => run.confirmation === 'WAITING'), [snapshot.pending]);
  const active = pending[0];
  const ringing = Boolean(active?.sound && !started[active.id] && !readStarted(active.id));

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!ringing || !connected) return;
    void playTaskReminderSound().catch(() => setError('提示音播放失败，请检查系统声音设置'));
    const timer = window.setInterval(() => {
      void playTaskReminderSound().catch(() => setError('提示音播放失败，请检查系统声音设置'));
    }, 4000);
    return () => window.clearInterval(timer);
  }, [ringing, connected]);

  async function finish(id: string) {
    if (finishing.current.has(id) || completed.current.has(id)) return;
    finishing.current.add(id); setBusy(id); setError('');
    try {
      await confirmAutomation(id);
      completed.current.add(id);
      localStorage.removeItem(startedKey(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '确认失败，请重试');
    } finally {
      finishing.current.delete(id); setBusy(null);
    }
  }

  useEffect(() => {
    for (const run of pending) {
      const task = snapshot.tasks.find(value => value.id === run.taskId);
      if (task?.spec.kind !== 'SEDENTARY') continue;
      const start = started[run.id] || readStarted(run.id);
      if (start && now >= start + task.spec.breakMinutes * 60_000 && !error && !completed.current.has(run.id)) void finish(run.id);
    }
  }, [pending, snapshot.tasks, started, now, error]);

  function acknowledge(id: string, sedentary: boolean) {
    void stopTaskReminderSound().catch(() => setError('提示音无法停止，请检查系统声音设置'));
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

  return <main className={styles.window}>
    <header className={styles.header}>
      <span className={styles.mark} aria-hidden="true">✦</span>
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
      {error && <p role="alert" className={styles.error}>{error}<button type="button" onClick={() => setError('')}>重试</button></p>}
      {active && remaining === null && <Button className={styles.primaryAction} variant="primary" disabled={Boolean(busy) || !connected} onClick={() => acknowledge(active.id, sedentary)}>{busy === active.id ? '处理中…' : sedentary ? '确认并开始休息' : '确认提醒'}</Button>}
      <Button className={styles.manageAction} variant="ghost" onClick={() => void openTaskMain().catch(() => setError('无法打开主窗口'))}>打开主窗口管理任务</Button>
    </footer>
  </main>;
}
