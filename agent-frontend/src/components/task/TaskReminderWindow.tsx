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
  const ringing = pending.some(run => run.sound && !started[run.id] && !readStarted(run.id));

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

  return <main className={styles.window}>
    <div className={styles.heading}><span className={styles.eyebrow}>任务提醒</span><h1>{pending.length ? '该休息一下了' : '正在同步提醒'}</h1></div>
    {!connected && <p role="status" className={styles.hint}>正在连接任务服务…</p>}
    <div className={styles.list}>
      {pending.map(run => {
        const task = snapshot.tasks.find(value => value.id === run.taskId);
        const sedentary = task?.spec.kind === 'SEDENTARY';
        const start = started[run.id] || readStarted(run.id);
        const remaining = start && sedentary ? Math.max(0, Math.ceil((start + task.spec.breakMinutes * 60_000 - now) / 1000)) : null;
        const time = remaining === null ? '' : `${String(Math.floor(remaining / 60)).padStart(2, '0')}:${String(remaining % 60).padStart(2, '0')}`;
        return <section key={run.id} className={styles.card}>
          <h2>{run.title}</h2><p>{run.content}</p>
          {remaining !== null ? <div className={styles.countdown} role="timer" aria-label="剩余休息时间">{time}<span>{remaining ? '休息中 · 结束后自动关闭' : '休息完成，正在确认…'}</span></div>
            : <><span className={styles.hint}>{sedentary ? `确认后开始休息 ${task.spec.breakMinutes} 分钟，提示音会停止。` : '确认后关闭提醒。'}</span><Button variant="primary" disabled={Boolean(busy) || !connected} onClick={() => acknowledge(run.id, sedentary)}>{busy === run.id ? '处理中…' : sedentary ? '确认并开始休息' : '确认提醒'}</Button></>}
        </section>;
      })}
    </div>
    {error && <p role="alert" className={styles.error}>{error} <Button variant="ghost" onClick={() => setError('')}>重试</Button></p>}
    <Button variant="ghost" onClick={() => void openTaskMain().catch(() => setError('无法打开主窗口'))}>打开主窗口管理任务</Button>
  </main>;
}
