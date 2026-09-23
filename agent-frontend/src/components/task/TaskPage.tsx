import { useEffect, useState } from 'react';
import { Bell, Copy, History, Pause, Pencil, Play, Plus, Search, Settings2, Trash2, Zap } from 'lucide-react';
import { useAutomations } from '../../context/automationState';
import { deleteAutomation, fetchAutomationHistory, retryAutomationEmail, runAutomation, setAutomationEnabled } from '../../services/automationApi';
import { runStatus, scheduleText, taskStatus } from '../../features/automation/presentation';
import type { AutomationTask, AutomationRun } from '../../types/automation';
import { TopBar } from '../common/TopBar';
import { TopBarAction } from '../common/TopBarAction';
import { Button } from '../common/Button';
import { Modal } from '../common/Modal';
import { useMessage } from '../common/Message';
import { TaskReadiness } from './TaskReadiness';
import { TaskEditor } from './TaskEditor';
import { TaskRunConfirmModal } from './TaskRunConfirmModal';
import styles from './TaskPage.module.css';

export function TaskPage({ onOpenSettings }: { onOpenSettings: () => void }) {
  const { snapshot, connected, activity } = useAutomations();
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
  const [confirm, setConfirm] = useState<{task:AutomationTask;action:'delete'|'run'} | null>(null);
  const [busy, setBusy] = useState(false);
  const { showMessage } = useMessage();
  useEffect(() => {
    if (!historyTask) return;
    let stopped = false;
    setHistoryLoading(true); setHistoryError('');
    void fetchAutomationHistory(historyTask.id).then(value => { if (!stopped) setHistory(value); }).catch(e => { if (!stopped) setHistoryError(e instanceof Error ? e.message : '历史读取失败'); }).finally(() => { if (!stopped) setHistoryLoading(false); });
    return () => { stopped = true; };
  }, [historyTask, snapshot, historyRefresh]);
  function switchEditor(next: {task?: AutomationTask; copy?: boolean} | null) {
    if (editorBusy) return;
    if (editorDirty) { setEditorSwitch({ next }); return; }
    applyEditor(next);
  }
  function applyEditor(next: {task?: AutomationTask; copy?: boolean} | null) {
    setEditor(next); setEditorDirty(false); setEditorKey(key => key + 1); setEditorSwitch(null);
  }
  async function act(work: () => Promise<unknown>, success: string) {
    setBusy(true);
    try { await work(); showMessage('success',success); setConfirm(null); }
    catch (e) { showMessage('error', e instanceof Error ? e.message : '操作失败'); }
    finally { setBusy(false); }
  }
  const tasks = snapshot.tasks.filter(t => (showDeleted ? t.status === 'DELETED' : filter === 'ALL' ? t.status !== 'DELETED' : t.status === filter) && t.spec.title.toLowerCase().includes(search.toLowerCase()));
  return <main className={styles.page}>
    <TopBar
      icon={<Bell size={19} />}
      title="任务"
      subtitle="自动提醒与每日汇总"
      search={
        <div className={styles.search}>
          <Search size={14} />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            aria-label="搜索任务"
            placeholder="搜索任务"
          />
        </div>
      }
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
        <div className={styles.tabs} role="tablist" aria-label="任务状态">
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
        </div>
      </div>
      <TaskReadiness onOpenSettings={onOpenSettings} />
      {!connected && <p role="status" className={styles.notice}>任务连接暂未就绪，正在自动重连。操作前请等待同步完成。</p>}

      {tasks.length === 0 ? <section className={styles.empty}><Search size={28} /><h2>{search ? '没有找到匹配任务' : showDeleted ? '没有已删除任务' : '让提醒按计划发生'}</h2><p>创建日报、久坐提醒或自定义通知，选择桌面或邮件接收。</p>{!showDeleted && <button type="button" className={styles.emptyAction} onClick={() => switchEditor({})}>创建第一个任务</button>}</section>
        : <div className={styles.list}>{tasks.map(task => <article key={task.id} className={styles.card}>
          <div className={styles.heading}><h2>{task.spec.title}</h2><span className={styles.badge}>{taskStatus[task.status] ?? task.status}</span></div>
          <p className={styles.rule}>{scheduleText(task.spec)}</p>
          <div className={styles.meta}><span>{task.spec.desktop ? '桌面通知' : ''}{task.spec.desktop && task.spec.email ? ' · ' : ''}{task.spec.email ? '邮件通知' : ''}{task.spec.confirm ? ' · 需确认' : ''}</span><span>{task.nextAt ? `下次：${new Date(task.nextAt).toLocaleString('zh-CN')}` : task.spec.kind === 'SEDENTARY' && task.status === 'ENABLED' ? (activity?.supported ? `累计 ${Math.floor(task.activeSeconds/60)} 分钟` : '当前环境不支持使用状态检测') : '暂无计划时间'}</span></div>
          <div className={styles.actions} role="group" aria-label={`${task.spec.title}的操作`}>
            <Button size="toolbar" variant="ghost" iconOnly aria-label="执行历史" title="执行历史"
              onClick={() => { setHistory([]); setHistoryTask(task); }}>
              <History size={16} aria-hidden="true" />
            </Button>
            {task.status !== 'DELETED' && <>
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
                disabled={busy || !connected || task.status !== 'ENABLED'} onClick={() => setConfirm({task,action:'run'})}>
                <Zap size={16} aria-hidden="true" />
              </Button>
              <Button size="toolbar" variant="ghost" iconOnly
                aria-label={task.status === 'ENABLED' ? '暂停' : '启用'} title={task.status === 'ENABLED' ? '暂停' : '启用'}
                disabled={busy || !connected} onClick={() => void act(() => setAutomationEnabled(task,task.status !== 'ENABLED'),'任务状态已更新')}>
                {task.status === 'ENABLED' ? <Pause size={16} aria-hidden="true" /> : <Play size={16} aria-hidden="true" />}
              </Button>
              <Button size="toolbar" variant="ghost" iconOnly aria-label="删除" title="删除"
                disabled={busy || !connected} onClick={() => setConfirm({task,action:'delete'})}>
                <Trash2 size={16} aria-hidden="true" />
              </Button>
            </>}
          </div>
        </article>)}</div>}
    </div></div>
    {editor && <TaskEditor key={editorKey} {...editor} onDirtyChange={setEditorDirty} onBusyChange={setEditorBusy} activitySupported={activity?.supported ?? false} onClose={() => switchEditor(null)} onSaved={() => applyEditor(null)} />}
    </div>
    <Modal open={Boolean(editorSwitch)} title="放弃未保存的修改？" onClose={() => setEditorSwitch(null)}>
      <p>当前任务有未保存的修改，切换后将丢弃这些内容。</p>
      <Button variant="outline" onClick={() => setEditorSwitch(null)}>继续编辑</Button>
      <Button variant="primary" onClick={() => { if (editorSwitch) applyEditor(editorSwitch.next); }}>放弃并继续</Button>
    </Modal>
    <TaskRunConfirmModal
      open={confirm?.action === 'run'}
      task={confirm?.action === 'run' ? confirm.task : null}
      busy={busy}
      onClose={() => {
        if (!busy) setConfirm(null);
      }}
      onConfirm={() => {
        if (confirm?.action === 'run') {
          void act(() => runAutomation(confirm.task.id), '任务已执行');
        }
      }}
    />
    <Modal
      open={confirm?.action === 'delete'}
      title="删除任务"
      width={440}
      centered
      onClose={() => {
        if (!busy) setConfirm(null);
      }}
    >
      <div className={styles.deleteConfirmBody}>
        <p className={styles.deleteConfirmText}>
          确定要删除任务<strong>「{confirm?.task.spec.title}」</strong>吗？
        </p>
        <p className={styles.deleteConfirmHint}>
          停止该任务并撤回未开始的通知，已有执行历史将保留。
        </p>
        <div className={styles.modalActions}>
          <Button variant="outline" disabled={busy} onClick={() => setConfirm(null)}>
            取消
          </Button>
          <Button
            variant="danger"
            disabled={busy}
            onClick={() => {
              if (confirm?.action === 'delete') {
                void act(() => deleteAutomation(confirm.task), '任务已删除');
              }
            }}
          >
            {busy ? '正在删除…' : '确认删除'}
          </Button>
        </div>
      </div>
    </Modal>
    <Modal open={Boolean(historyTask)} title={`${historyTask?.spec.title ?? ''} · 执行历史`} width={720} onClose={() => setHistoryTask(null)}><div className={styles.history}>
      {historyError && <p role="alert">{historyError}<Button variant="ghost" onClick={() => setHistoryRefresh(value => value + 1)}>重试</Button></p>}
      {historyLoading && <p role="status">正在同步执行记录…</p>}
      {history.length === 0 && !historyError && !historyLoading && <p>暂无执行记录。</p>}
      {history.map(run => <section key={run.id} className={styles.record}><div className={styles.heading}><strong>{new Date(run.createdAt).toLocaleString('zh-CN')}</strong><span>{runStatus[run.status] ?? run.status}</span></div><p className={styles.meta}>桌面：{runStatus[run.desktopStatus]} · 邮件：{runStatus[run.emailStatus]} · {runStatus[run.confirmation]}</p>{run.error && <p role="status">{run.error}</p>}<pre>{run.content}</pre>{(run.emailStatus === 'FAILED' || run.emailStatus === 'UNKNOWN') &&
        <Button size="sm" variant="outline" disabled={busy} onClick={() => setRetryRun(run)}>重发原邮件</Button>}</section>)}
    </div></Modal>
    <Modal open={Boolean(retryRun)} title="重发邮件" onClose={() => setRetryRun(null)}>
      <p>{retryRun?.emailStatus === 'UNKNOWN' ? '上次发送结果未知，邮件可能已经送达。再次发送可能产生重复邮件。' : '将复用这次执行已保存的内容，不重新生成日报。'}</p>
      <Button variant="primary" disabled={busy} onClick={() => { if (retryRun) void act(async () => { await retryAutomationEmail(retryRun.id, retryRun.emailStatus === 'UNKNOWN'); if (historyTask) setHistory(await fetchAutomationHistory(historyTask.id)); setRetryRun(null); }, '邮件已加入发送队列'); }}>确认重发</Button>
    </Modal>
  </main>;
}
