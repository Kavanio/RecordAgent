import { useEffect, useRef, useState } from 'react';
import { Bell, Copy, History, Pause, Pencil, Play, Plus, RotateCcw, Search, Settings2, Trash2, Zap } from 'lucide-react';
import { useAutomations } from '../../context/automationState';
import { deleteAutomation, fetchAutomationHistory, purgeDeletedAutomations, restoreAutomation, retryAutomationEmail, runAutomation, setAutomationEnabled } from '../../services/automationApi';
import { scheduleText, taskStatus } from '../../features/automation/presentation';
import type { AutomationTask, AutomationRun } from '../../types/automation';
import { TopBar } from '../common/TopBar';
import { TopBarAction } from '../common/TopBarAction';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { LoadingTree } from '../common/LoadingTree';
import { useMessage } from '../common/Message';
import { TaskEditor } from './TaskEditor';
import { TaskHistoryDrawer } from './TaskHistoryDrawer';
import { TaskRunConfirmModal } from './TaskRunConfirmModal';
import { TaskSyncStatus } from './TaskSyncStatus';
import styles from './TaskPage.module.css';


export function TaskPage({ onOpenSettings }: { onOpenSettings: () => void }) {
  const { snapshot, connected, syncStatus, lastSyncedAt, historyRevision, refreshSnapshot, retrySync, activity, activitySyncError } = useAutomations();
  const hasSnapshot = lastSyncedAt !== null;
  const [search, setSearch] = useState('');
  const [showDeleted, setShowDeleted] = useState(false);
  const [editor, setEditor] = useState<{task?:AutomationTask;copy?:boolean} | null>(null);
  const [editorDirty, setEditorDirty] = useState(false);
  const [editorBusy, setEditorBusy] = useState(false);
  const [editorSwitch, setEditorSwitch] = useState<{ next: {task?: AutomationTask; copy?: boolean} | null } | null>(null);
  const [editorKey, setEditorKey] = useState(0);
  const [filter, setFilter] = useState<'ALL' | 'ENABLED' | 'PAUSED' | 'FINISHED' | 'DELETED'>('ALL');
  const [historyTask, setHistoryTask] = useState<AutomationTask | null>(null);
  const [history, setHistory] = useState<AutomationRun[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyRefresh, setHistoryRefresh] = useState(0);
  const [historyError, setHistoryError] = useState('');
  const [retryRun, setRetryRun] = useState<AutomationRun | null>(null);
  const [purgeConfirm, setPurgeConfirm] = useState(false);
  const [confirm, setConfirm] = useState<{task:AutomationTask;action:'delete'|'run'} | null>(null);
  const [busy, setBusy] = useState(false);
  const { showMessage } = useMessage();
  const connectionNoticeShown = useRef(false);
  useEffect(() => {
    if (connected) {
      if (connectionNoticeShown.current) showMessage('success', '任务同步已恢复');
      connectionNoticeShown.current = false;
      return;
    }
    if (connectionNoticeShown.current) return;
    // 短暂重连不打扰用户；保留数据，操作前通过 REST 校验，不把推送中断等同于任务停止。
    const timer = window.setTimeout(() => {
      connectionNoticeShown.current = true;
      showMessage('info', hasSnapshot ? '任务实时同步中断，正在自动重连；操作前将校验最新状态' : '暂未连接任务服务，请检查后端连接或重试', {
        action: { label: '重试连接', onClick: retrySync },
      });
    }, 1500);
    return () => window.clearTimeout(timer);
  }, [connected, hasSnapshot, showMessage, retrySync]);
  useEffect(() => {
    if (!historyTask) return;
    let stopped = false;
    setHistoryLoading(true); setHistoryError('');
    void fetchAutomationHistory(historyTask.id).then(value => { if (!stopped) setHistory(value); }).catch(e => { if (!stopped) setHistoryError(e instanceof Error ? e.message : '历史读取失败'); }).finally(() => { if (!stopped) setHistoryLoading(false); });
    return () => { stopped = true; };
  }, [historyTask, historyRevision, historyRefresh]);
  function switchEditor(next: {task?: AutomationTask; copy?: boolean} | null) {
    if (editorBusy) return;
    if (editorDirty) { setEditorSwitch({ next }); return; }
    applyEditor(next);
  }
  function applyEditor(next: {task?: AutomationTask; copy?: boolean} | null) {
    setEditor(next); setEditorDirty(false); setEditorKey(key => key + 1); setEditorSwitch(null);
  }
  function openHistory(task: AutomationTask) {
    setHistory([]); setHistoryTask(task);
  }
  function refreshAfterWrite() {
    void refreshSnapshot().catch(() => showMessage('info', '操作已完成，列表暂未刷新，请重试同步', {
      action: { label: '重试连接', onClick: retrySync },
    }));
  }
  async function act(work: () => Promise<unknown>, success: string, task?: AutomationTask) {
    if (busy) return;
    setBusy(true);
    let attemptedWrite = false;
    try {
      if (!connected) {
        const latest = await refreshSnapshot();
        const current = task && latest.tasks.find(value => value.id === task.id);
        if (task && (!current || current.version !== task.version || current.status !== task.status)) {
          throw new Error('任务状态已变化，列表已刷新，请核对后重试');
        }
      }
      attemptedWrite = true;
      await work(); showMessage('success',success); setConfirm(null);
      refreshAfterWrite();
    } catch (e) {
      showMessage('error', e instanceof Error ? e.message : '操作失败');
      // 版本冲突只刷新视图，不自动重放有副作用的操作。
      if (attemptedWrite) void refreshSnapshot().catch(() => retrySync());
    } finally { setBusy(false); }
  }
  const tasks = snapshot.tasks.filter(t => (showDeleted ? t.status === 'DELETED' : filter === 'ALL' ? t.status !== 'DELETED' : t.status === filter) && t.spec.title.toLowerCase().includes(search.toLowerCase()));
  const deletedCount = snapshot.tasks.filter(task => task.status === 'DELETED').length;
  return <main className={styles.page}>
    <TopBar
      icon={<Bell size={19} />}
      title="任务"
      subtitle="自动提醒与每日汇总"
      actions={
        <div className={styles.headerActions}>
          <TopBarAction variant="ghost" onClick={() => setShowDeleted(value => !value)}>
            {showDeleted ? '返回任务' : '已删除'}
          </TopBarAction>
          <TopBarAction variant="ghost" iconOnly aria-label="任务设置" icon={<Settings2 size={18} />} onClick={onOpenSettings}>
            <span className={styles.visuallyHidden}>任务设置</span>
          </TopBarAction>
          <TopBarAction variant="primary" icon={<Plus size={15} />} onClick={() => switchEditor({})}>
            新建任务
          </TopBarAction>
        </div>
      }
    />
    <div className={styles.workspace}><div className={styles.content}><div className={styles.contentInner}>
      <div className={styles.toolbar}>
        {showDeleted ? <span className={styles.deletedTitle}>已删除任务</span> : <div className={styles.tabs} role="tablist" aria-label="任务状态">
          {(['ALL','ENABLED','PAUSED','FINISHED'] as const).map(value => (
            <button
              key={value}
              type="button"
              role="tab"
              aria-selected={filter === value && !showDeleted}
              className={filter === value && !showDeleted ? styles.activeTab : ''}
              onClick={() => { setShowDeleted(false); setFilter(value); }}
            >
              {value === 'ALL' ? '全部' : value === 'ENABLED' ? '已开启' : value === 'PAUSED' ? '已暂停' : '已结束'}
            </button>
          ))}
        </div>}
        <div className={styles.toolbarActions}>
          <TaskSyncStatus status={syncStatus} lastSyncedAt={lastSyncedAt} onRetry={retrySync} />
          <label className={styles.search}>
            <Search size={16} aria-hidden="true" />
            <input value={search} onChange={e => setSearch(e.target.value)} aria-label="搜索任务" placeholder="搜索任务" />
          </label>
          {showDeleted && <Button size="sm" variant="ghost" icon={<Trash2 size={15} />}
            disabled={busy || deletedCount === 0} onClick={() => setPurgeConfirm(true)}>
            清空
          </Button>}
        </div>
      </div>

      {!hasSnapshot ? <section className={styles.empty}>
        {syncStatus === 'OFFLINE' ? <p role="status">暂时无法读取任务，请检查后端连接并重试同步。</p> : <LoadingTree label="正在读取任务…" />}
      </section> : tasks.length === 0 ? <section className={styles.empty}><Search size={28} /><h2>{search ? '没有找到匹配任务' : showDeleted ? '没有已删除任务' : '让提醒按计划发生'}</h2><p>创建日报、周报、月报、久坐提醒或自定义通知，选择桌面或邮件接收。</p>{!showDeleted && <button type="button" className={styles.emptyAction} onClick={() => switchEditor({})}>创建第一个任务</button>}</section>
        : <div className={styles.list}>{tasks.map(task => <article key={task.id} className={styles.card}>
          <div className={styles.heading}><h2>{task.spec.title}</h2><span className={styles.badge}>{taskStatus[task.status] ?? task.status}</span></div>
          <p className={styles.rule}>{scheduleText(task.spec)}</p>
          <div className={styles.meta}><span>{task.spec.desktop ? '桌面通知' : ''}{task.spec.desktop && task.spec.email ? ' · ' : ''}{task.spec.email ? '邮件通知' : ''}{task.spec.confirm ? ' · 需确认' : ''}</span><span>{task.nextAt ? `下次：${new Date(task.nextAt).toLocaleString('zh-CN')}` : task.spec.kind === 'SEDENTARY' && task.status === 'ENABLED' ? (activity?.supported ? `累计 ${Math.floor(task.activeSeconds/60)} 分钟` : '当前环境不支持使用状态检测') : '暂无计划时间'}</span></div>
          {task.spec.kind === 'SEDENTARY' && task.status === 'ENABLED' && activitySyncError && <p className={styles.syncError} role="status">使用时长同步失败：{activitySyncError}</p>}
          <div className={styles.actions} role="group" aria-label={`${task.spec.title}的操作`}>
            <Button size="toolbar" variant="ghost" iconOnly aria-label="执行历史" title="执行历史"
              onClick={() => openHistory(task)}>
              <History size={16} aria-hidden="true" />
            </Button>
            {task.status === 'DELETED' ? <Button size="sm" variant="ghost" icon={<RotateCcw size={15} />}
              disabled={busy} onClick={() => void act(() => restoreAutomation(task), '任务已恢复为暂停状态', task)}>
              恢复
            </Button> : <>
              <Button size="toolbar" variant="ghost" iconOnly aria-label="编辑" title="编辑"
                onClick={() => switchEditor({task})}>
                <Pencil size={16} aria-hidden="true" />
              </Button>
              <Button size="toolbar" variant="ghost" iconOnly aria-label="复制" title="复制"
                onClick={() => switchEditor({task,copy:true})}>
                <Copy size={16} aria-hidden="true" />
              </Button>
              <Button size="toolbar" variant="ghost" iconOnly aria-label="立即执行"
                title={task.status !== 'ENABLED' ? '启用任务后可立即执行' : '立即执行'}
                disabled={busy || task.status !== 'ENABLED'} onClick={() => setConfirm({task,action:'run'})}>
                <Zap size={16} aria-hidden="true" />
              </Button>
              <Button size="toolbar" variant="ghost" iconOnly
                aria-label={task.status === 'ENABLED' ? '暂停' : '启用'} title={task.status === 'ENABLED' ? '暂停' : '启用'}
                disabled={busy} onClick={() => void act(() => setAutomationEnabled(task,task.status !== 'ENABLED'),'任务状态已更新', task)}>
                {task.status === 'ENABLED' ? <Pause size={16} aria-hidden="true" /> : <Play size={16} aria-hidden="true" />}
              </Button>
              <Button size="toolbar" variant="ghost" iconOnly aria-label="删除" title="删除"
                disabled={busy} onClick={() => setConfirm({task,action:'delete'})}>
                <Trash2 size={16} aria-hidden="true" />
              </Button>
            </>}
          </div>
        </article>)}</div>}
    </div></div>
    {editor && <TaskEditor key={editorKey} {...editor} onDirtyChange={setEditorDirty} onBusyChange={setEditorBusy} activitySupported={activity?.supported ?? false} onClose={() => switchEditor(null)} onSaved={() => { applyEditor(null); refreshAfterWrite(); }} />}
    {historyTask && <TaskHistoryDrawer task={historyTask} runs={history} loading={historyLoading} error={historyError} busy={busy}
      onClose={() => setHistoryTask(null)} onReload={() => setHistoryRefresh(value => value + 1)} onRetryEmail={setRetryRun} />}
    </div>
    <ConfirmDialog
      open={purgeConfirm}
      title="清空已删除任务"
      tone="danger"
      confirmText="永久清空"
      confirmVariant="danger"
      busy={busy}
      description={`将永久删除 ${deletedCount} 个任务及其全部执行历史，无法恢复。`}
      onClose={() => { if (!busy) setPurgeConfirm(false); }}
      onConfirm={() => {
        void act(async () => { await purgeDeletedAutomations(); setPurgeConfirm(false); }, '已清空已删除任务');
      }}
    />
    <ConfirmDialog
      open={Boolean(editorSwitch)}
      title="放弃未保存的修改？"
      tone="warning"
      description="当前任务有未保存的修改，切换后将丢弃这些内容。"
      cancelText="继续编辑"
      confirmText="放弃并继续"
      onClose={() => setEditorSwitch(null)}
      onConfirm={() => {
        if (editorSwitch) applyEditor(editorSwitch.next);
      }}
    />
    <TaskRunConfirmModal
      open={confirm?.action === 'run'}
      task={confirm?.action === 'run' ? confirm.task : null}
      busy={busy}
      onClose={() => {
        if (!busy) setConfirm(null);
      }}
      onConfirm={() => {
        if (confirm?.action === 'run') {
          void act(() => runAutomation(confirm.task.id), '任务已执行', confirm.task);
        }
      }}
    />
    <ConfirmDialog
      open={confirm?.action === 'delete'}
      title="删除任务"
      tone="danger"
      confirmText="确认删除"
      confirmVariant="danger"
      busy={busy}
      onClose={() => {
        if (!busy) setConfirm(null);
      }}
      onConfirm={() => {
        if (confirm?.action === 'delete') {
          void act(() => deleteAutomation(confirm.task), '任务已删除', confirm.task);
        }
      }}
    >
      <div className={styles.deleteConfirmBody}>
        <p className={styles.deleteConfirmText}>
          确定要删除任务<strong>「{confirm?.task.spec.title}」</strong>吗？
        </p>
        <p className={styles.deleteConfirmHint}>
          停止该任务并撤回未开始的通知，已有执行历史将保留。
        </p>
      </div>
    </ConfirmDialog>

    <ConfirmDialog
      open={Boolean(retryRun)}
      title="重发邮件"
      tone="info"
      confirmText="确认重发"
      busy={busy}
      description={
        retryRun?.emailStatus === 'UNKNOWN'
          ? '上次发送结果未知，邮件可能已经送达。再次发送可能产生重复邮件。'
          : '将复用这次执行已保存的内容，不重新生成报告。'
      }
      onClose={() => setRetryRun(null)}
      onConfirm={() => {
        if (retryRun) {
          void act(async () => {
            await retryAutomationEmail(retryRun.id, retryRun.emailStatus === 'UNKNOWN');
            if (historyTask) setHistory(await fetchAutomationHistory(historyTask.id));
            setRetryRun(null);
          }, '邮件已加入发送队列');
        }
      }}
    />

  </main>;
}
