import { useState } from 'react';
import { useAutomations } from '../../context/automationState';
import { confirmAutomation } from '../../services/automationApi';
import { openTaskMain } from '../../services/taskDesktop';
import { Button } from '../common/Button';
import styles from './TaskReminderWindow.module.css';
/** 待确认状态由后端持久化，清除系统通知不会关闭此提醒。 */
export function TaskReminderWindow() {
  const { snapshot, connected } = useAutomations();
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');
  async function confirm(id: string) {
    setBusy(id); setError('');
    try { await confirmAutomation(id); }
    catch (e) { setError(e instanceof Error ? e.message : '确认失败，请重试'); }
    finally { setBusy(null); }
  }
  const pending = snapshot.pending.filter(r => r.confirmation === 'WAITING');
  return <main className={styles.window}>
    <h1>待确认提醒</h1><p className={styles.hint}>确认表示已处理此提醒；久坐任务确认后重新计时。</p>
    {!connected && <p role="status">正在连接任务服务…</p>}
    {pending.map(run => <section key={run.id} className={styles.card}><h2>{run.title}</h2><p>{run.content}</p><small>{new Date(run.createdAt).toLocaleString('zh-CN')}</small><Button variant="primary" disabled={Boolean(busy) || !connected} onClick={() => void confirm(run.id)}>{busy === run.id ? '正在确认…' : '确认提醒'}</Button></section>)}
    {connected && pending.length === 0 && <p>当前没有待确认提醒。</p>}
    {error && <p role="alert">{error}</p>}
    <Button variant="ghost" onClick={() => void openTaskMain().catch(() => setError('无法打开主窗口'))}>打开主窗口管理任务</Button>
  </main>;
}
