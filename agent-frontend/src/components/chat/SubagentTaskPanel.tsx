import React from 'react';
import { Bot, CircleAlert, CircleCheck, CircleX, Clock3, RefreshCw, Square } from 'lucide-react';
import type { TaskDto } from '../../types/team';
import { Button } from '../common/Button';
import { LoadingTree } from '../common/LoadingTree';
import styles from './SubagentTaskPanel.module.css';

export interface SubagentTaskPanelProps {
  tasks: TaskDto[];
  isLoading: boolean;
  error: string | null;
  cancellingTaskId: string | null;
  onRefresh: () => void;
  onCancel: (taskId: string) => void;
}

function isTerminal(status: string): boolean {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(status.toUpperCase());
}

function statusLabel(status: string): string {
  const labels: Record<string, string> = {
    PENDING: '等待中', RUNNING: '执行中', COMPLETED: '已完成', FAILED: '失败',
    CANCELLED: '已取消', CANCELED: '已取消',
  };
  return labels[status.toUpperCase()] || status || '未知状态';
}

function StatusIcon({ status }: { status: string }) {
  const normalized = status.toUpperCase();
  if (normalized === 'COMPLETED') return <CircleCheck className={styles.successIcon} size={15} aria-hidden="true" />;
  if (normalized === 'FAILED') return <CircleX className={styles.errorIcon} size={15} aria-hidden="true" />;
  if (normalized === 'CANCELLED' || normalized === 'CANCELED') {
    return <CircleAlert className={styles.mutedIcon} size={15} aria-hidden="true" />;
  }
  return <Clock3 className={styles.runningIcon} size={15} aria-hidden="true" />;
}

/** 当前会话后台子 Agent 任务的状态与取消入口。 */
export const SubagentTaskPanel: React.FC<SubagentTaskPanelProps> = ({
  tasks, isLoading, error, cancellingTaskId, onRefresh, onCancel,
}) => {
  const activeCount = tasks.filter((task) => !isTerminal(task.status)).length;

  return (
    <section className={styles.content} aria-label="后台子 Agent 任务">
      <div className={styles.header}>
        <div>
          <div className={styles.title}><Bot size={16} aria-hidden="true" /> 子 Agent 任务</div>
          <p className={styles.summary}>{activeCount > 0 ? `${activeCount} 项正在执行` : '当前会话的后台任务'}</p>
        </div>
        <button type="button" className={styles.refresh} onClick={onRefresh} disabled={isLoading} aria-label="刷新子 Agent 任务" title="刷新">
          <RefreshCw size={15} className={isLoading ? styles.refreshing : ''} aria-hidden="true" />
        </button>
      </div>

      {error ? (
        <p className={styles.error} role="status">{error}</p>
      ) : isLoading && tasks.length === 0 ? (
        <div className={styles.loadingState}><LoadingTree size="small" label="正在读取任务…" /></div>
      ) : tasks.length === 0 ? (
        <p className={styles.empty}>子 Agent 启动后台任务后会显示在这里。</p>
      ) : (
        <div className={styles.taskList}>
          {tasks.map((task) => {
            const terminal = isTerminal(task.status);
            const detail = task.error || task.result;
            const isCancelling = cancellingTaskId === task.taskId;
            return (
              <article className={styles.task} key={task.taskId}>
                <div className={styles.taskHeader}>
                  <StatusIcon status={task.status} />
                  <span className={styles.taskId} title={task.taskId}>任务 {String(task.taskId || '').slice(0, 8)}</span>
                  <span className={terminal ? styles.terminalStatus : styles.activeStatus}>{statusLabel(task.status)}</span>
                </div>
                {detail && <p className={task.error ? styles.taskError : styles.result}>{detail}</p>}
                {!terminal && (
                  <Button variant="ghost" size="sm" className={styles.cancel} icon={<Square size={12} aria-hidden="true" />} onClick={() => onCancel(task.taskId)} disabled={isCancelling}>
                    {isCancelling ? '取消中…' : '取消任务'}
                  </Button>
                )}
              </article>
            );
          })}
        </div>
      )}
    </section>
  );
};
