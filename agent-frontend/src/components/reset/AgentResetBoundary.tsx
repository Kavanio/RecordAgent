import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from '../common/Button';
import { fetchBackendConnection, getApiBaseUrl, restartApplication } from '../../services/api';
import { fetchAgentReset, resetBlocked, retryAgentReset, subscribeAgentReset } from '../../services/agentResetService';
import type { AgentResetStatus } from '../../types/agentReset';
import styles from './AgentResetBoundary.module.css';

/** 启动先确认屏障；状态代次改变时卸载旧应用树，丢弃会话、图片草稿和迟到组件响应。 */
export function AgentResetBoundary({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AgentResetStatus | null>(null);
  const [error, setError] = useState('');
  const [receipt, setReceipt] = useState(false);
  const [canRestart, setCanRestart] = useState(false);
  const seenBlocked = useRef(false);
  useEffect(() => {
    let mounted = true;
    let timer: ReturnType<typeof setTimeout>;
    const unsubscribe = subscribeAgentReset(next => {
      if (!mounted) return;
      if (resetBlocked(next)) seenBlocked.current = true;
      if (next.status === 'LOCAL_COMPLETE' && seenBlocked.current) setReceipt(true);
      setStatus(previous => previous && (previous.generation > next.generation
        || (previous.generation === next.generation && previous.status === 'LOCAL_COMPLETE')) ? previous : next);
      setError('');
    });
    async function refresh() {
      try { await fetchAgentReset(); }
      catch (cause) { if (mounted) setError(cause instanceof Error ? cause.message : '无法确认重置状态'); }
      finally { if (mounted) timer = setTimeout(() => void refresh(), 2000); }
    }
    void refresh();
    void fetchBackendConnection().then(info => { if (mounted) setCanRestart(info.mode === 'sidecar'); }).catch(() => undefined);
    return () => { mounted = false; clearTimeout(timer); unsubscribe(); };
  }, []);
  async function retry() {
    if (!status?.resetId) return;
    try { await retryAgentReset(status.resetId); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '重试失败'); }
  }
  async function restart() {
    try { await restartApplication(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '重启失败，请正常退出后重新启动'); }
  }
  if (status && !resetBlocked(status) && !receipt) return <Fragment key={status.generation}>{children}</Fragment>;
  return <main className={styles.page} aria-label="Agent 恢复出厂进度">
    <section className={styles.card}>
      <h1>{status?.status === 'LOCAL_COMPLETE' ? '本地重置完成' : 'Agent 恢复出厂设置'}</h1>
      <p>当前实际后端：{getApiBaseUrl()}</p>
      <p role="status">{status?.message || (status ? '正在停止旧任务并清理个人上下文…' : '正在确认重置状态…')}</p>
      <p>Hindsight 尚未接入，未执行远端清理。手记、资料、附件和其他业务数据保留。</p>
      {status?.status === 'WAITING_RESTART' && <p>{canRestart
        ? '请正常重启应用。启动后将自动续做当前任务。'
        : '请正常重启上述地址对应的后端进程；刷新页面或重启桌面前端不能重启远程／开发后端。'}</p>}
      {error && <p role="alert">{error}</p>}
      <div className={styles.actions}>
        {status?.retryable && <Button variant="outline" onClick={() => void retry()}>继续原重置任务</Button>}
        {status?.status === 'WAITING_RESTART' && canRestart && <Button onClick={() => void restart()}>正常重启应用</Button>}
        {receipt && <Button variant="primary" onClick={() => { seenBlocked.current = false; setReceipt(false); }}>开始新对话</Button>}
      </div>
    </section>
  </main>;
}
