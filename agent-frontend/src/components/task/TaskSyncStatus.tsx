import type { AutomationSyncStatus } from '../../types/automation';
import { Button } from '../common/Button';
import styles from './TaskSyncStatus.module.css';

interface Props {
  status: AutomationSyncStatus;
  lastSyncedAt: number | null;
  onRetry: () => void;
}
const labels: Record<AutomationSyncStatus, string> = {
  CONNECTING: '正在连接', LIVE: '已同步', RECONNECTING: '正在重连', OFFLINE: '连接中断',
};

/** 稳定展示实时连接状态；重试只重新订阅快照，不重放任务操作。 */
export function TaskSyncStatus({ status, lastSyncedAt, onRetry }: Props) {
  return <div className={styles.status} aria-label="任务同步状态">
    <span className={styles.label} data-state={status} role="status"
      title={lastSyncedAt !== null ? `最近更新：${new Date(lastSyncedAt).toLocaleString('zh-CN')}` : '尚未取得任务快照'}>
      <span className={styles.dot} aria-hidden="true" />{labels[status]}
    </span>
    {status !== 'LIVE' && <Button size="sm" variant="ghost" aria-label="重试任务同步"
      title="重新连接并读取最新任务，不会重复执行任务" onClick={onRetry}>重试连接</Button>}
  </div>;
}
