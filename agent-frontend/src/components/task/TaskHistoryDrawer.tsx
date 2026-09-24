import { CalendarClock, History, Mail, Monitor, RotateCcw } from 'lucide-react';
import { runStatus, scheduleText, taskStatus } from '../../features/automation/presentation';
import type { AutomationRun, AutomationTask } from '../../types/automation';
import { Button } from '../common/Button';
import { Drawer } from '../common/Drawer';
import styles from './TaskHistoryDrawer.module.css';

interface TaskHistoryDrawerProps {
  task: AutomationTask;
  runs: AutomationRun[];
  loading: boolean;
  error: string;
  busy: boolean;
  onClose: () => void;
  onReload: () => void;
  onRetryEmail: (run: AutomationRun) => void;
}

/** 在任务工作区右侧展示一个任务的执行记录与投递结果。 */
export function TaskHistoryDrawer({ task, runs, loading, error, busy, onClose, onReload, onRetryEmail }: TaskHistoryDrawerProps) {
  return <Drawer open title={`${task.spec.title} · 执行历史`} description="查看每次生成内容与通知渠道的投递结果" width={520} onClose={onClose}>
    <div className={styles.body} aria-label={`${task.spec.title}的执行历史`}>
      <section className={styles.summary} aria-label="任务概况">
        <div className={styles.summaryTop}>
          <span className={styles.taskStatus}>{taskStatus[task.status] ?? task.status}</span>
          <strong>{runs.length}</strong><span>次执行</span>
        </div>
        <p><CalendarClock size={14} aria-hidden="true" />{scheduleText(task.spec)}</p>
      </section>

      {error && <section className={styles.feedback} role="alert">
        <strong>历史记录读取失败</strong><p>{error}</p>
        <Button size="sm" variant="outline" icon={<RotateCcw size={13} />} onClick={onReload}>重新加载</Button>
      </section>}
      {loading && <div className={styles.feedback} role="status"><span className={styles.spinner} />正在同步执行记录…</div>}
      {!loading && !error && runs.length === 0 && <section className={styles.empty}>
        <span className={styles.emptyIcon}><History size={22} /></span>
        <strong>暂无执行记录</strong>
        <p>任务执行后，生成结果和各通知渠道状态会显示在这里。</p>
      </section>}

      {!error && runs.length > 0 && <div className={styles.timeline}>
        {runs.map(run => <article key={run.id} className={styles.record}>
          <span className={styles.timelineDot} aria-hidden="true" />
          <div className={styles.recordHeader}>
            <time dateTime={new Date(run.createdAt).toISOString()}>{new Date(run.createdAt).toLocaleString('zh-CN')}</time>
            <span className={styles.runStatus}>{runStatus[run.status] ?? run.status}</span>
          </div>
          <div className={styles.channels}>
            <span><Monitor size={13} aria-hidden="true" />桌面 {runStatus[run.desktopStatus] ?? run.desktopStatus}</span>
            <span><Mail size={13} aria-hidden="true" />邮件 {runStatus[run.emailStatus] ?? run.emailStatus}</span>
            <span>确认 {runStatus[run.confirmation] ?? run.confirmation}</span>
          </div>
          {run.error && <p className={styles.runError} role="status">{run.error}</p>}
          {run.content && <details className={styles.content}>
            <summary>查看本次生成内容</summary>
            <pre>{run.content}</pre>
          </details>}
          {(run.emailStatus === 'FAILED' || run.emailStatus === 'UNKNOWN') &&
            <Button size="sm" variant="outline" disabled={busy} onClick={() => onRetryEmail(run)}>重发原邮件</Button>}
        </article>)}
      </div>}
    </div>
  </Drawer>;
}
