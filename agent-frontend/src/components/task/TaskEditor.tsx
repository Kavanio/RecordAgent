import { useRef, useState, type FormEvent } from 'react';
import type { AutomationSpec, AutomationTask } from '../../types/automation';
import { newAutomation, scheduleText } from '../../features/automation/presentation';
import { saveAutomation } from '../../services/automationApi';
import { TaskPreviewModal } from './TaskPreviewModal';
import { TaskMailTemplateEditor } from './TaskMailTemplateEditor';
import { Button } from '../common/Button';
import { X } from 'lucide-react';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import { Select } from '../common/Select';
import { useMessage } from '../common/Message';
import styles from './TaskEditor.module.css';

interface Props { task?: AutomationTask; copy?: boolean; onClose: () => void; onSaved: () => void; activitySupported: boolean; onDirtyChange: (dirty: boolean) => void; onBusyChange: (busy: boolean) => void }
export function TaskEditor({ task, copy, onClose, onSaved, activitySupported, onDirtyChange, onBusyChange }: Props) {
  const [spec, setSpec] = useState<AutomationSpec>(task?.spec ?? newAutomation());
  const [enabled, setEnabled] = useState(copy ? false : task ? task.status === 'ENABLED' : true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [previewOpen, setPreviewOpen] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const { showMessage } = useMessage();
  function update<K extends keyof AutomationSpec>(key: K, value: AutomationSpec[K]) { onDirtyChange(true); setSpec(s => ({ ...s, [key]: value,
    ...(key === 'desktop' && !value ? { sound: false, confirm: false } : {}),
  })); setPreviewOpen(false); }
  function validate() {
    if (!formRef.current?.reportValidity()) return false;
    const message = !spec.title.trim() ? '请输入任务标题'
      : !spec.desktop && !spec.email ? '至少选择一种通知方式'
      : !spec.weekdays ? '至少选择一个生效星期'
      : spec.kind.endsWith('_REPORT') && !spec.expense && !spec.todo && !spec.study ? '至少选择一个报告栏目' : '';
    setError(message);
    return !message;
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (!validate()) return;
    setBusy(true); onBusyChange(true); setError('');
    try { await saveAutomation(spec, enabled, copy ? undefined : task); showMessage('success', '任务已保存'); onSaved(); }
    catch (e) { setError(e instanceof Error ? e.message : '保存失败'); }
    finally { setBusy(false); onBusyChange(false); }
  }
  function inspect() {
    if (validate()) setPreviewOpen(true);
  }
  const checkbox = (key: 'desktop' | 'email' | 'confirm' | 'sound' | 'expense' | 'todo' | 'study', label: string, disabled = false) =>
    <label className={styles.check}><input type="checkbox" checked={spec[key]} disabled={disabled || busy} onChange={e => update(key, e.target.checked)} />{label}</label>;
  const localOnce = spec.onceAt ? new Date(new Date(spec.onceAt).getTime() - new Date(spec.onceAt).getTimezoneOffset() * 60000).toISOString().slice(0,16) : '';
  return <div className={styles.drawer} role="dialog" aria-modal="false" aria-label={task && !copy ? '编辑任务' : '新建任务'}><header className={styles.drawerHeader}><div><span>{task && !copy ? '编辑' : '新建'}</span><h2>{task && !copy ? '编辑任务' : '创建任务'}</h2></div><button type="button" aria-label="关闭任务编辑" disabled={busy} onClick={onClose}><X size={20} /></button></header>
    <form ref={formRef} className={styles.form} onSubmit={save}>
      <fieldset disabled={busy} className={styles.group}><legend>基本信息</legend>
        <Select label="任务模板" value={spec.kind} disabled={Boolean(task && !copy)} options={[
          { value: 'REMINDER', label: '自定义提醒' }, { value: 'DAILY_REPORT', label: '每日日报' },
          { value: 'WEEKLY_REPORT', label: '每周周报' }, { value: 'MONTHLY_REPORT', label: '每月月报' },
          { value: 'SEDENTARY', label: '久坐提醒（macOS）', disabled: !activitySupported },
        ]} onChange={e => { onDirtyChange(true); setSpec(newAutomation(e.target.value as AutomationSpec['kind'])); setPreviewOpen(false); }} />
        <FormField label="任务标题" htmlFor="task-title" required><TextInput id="task-title" value={spec.title} maxLength={100} required onChange={e => update('title', e.target.value)} /></FormField>
        {!spec.kind.endsWith('_REPORT') && <FormField label="提醒内容" htmlFor="task-content"><textarea id="task-content" value={spec.content} maxLength={4000} rows={3} onChange={e => update('content', e.target.value)} /></FormField>}
      </fieldset>
      <fieldset disabled={busy} className={styles.group}><legend>触发规则</legend>
        {spec.kind === 'REMINDER' && <Select label="触发方式" value={spec.trigger} options={[
          { value:'ONCE',label:'指定日期一次' },{value:'DAILY',label:'每日'},{value:'WEEKLY',label:'每周'},{value:'INTERVAL',label:'按间隔'},
        ]} onChange={e => update('trigger', e.target.value as AutomationSpec['trigger'])} />}
        {spec.trigger === 'ONCE' ? <FormField label="执行时间（本机时间）" htmlFor="task-once"><TextInput id="task-once" type="datetime-local" required value={localOnce} onChange={e => update('onceAt', e.target.value ? new Date(e.target.value).toISOString() : null)} /></FormField>
          : spec.trigger === 'INTERVAL' || spec.trigger === 'ACTIVITY' ? <FormField label={spec.trigger === 'ACTIVITY' ? '连续使用分钟数' : '间隔分钟数'} htmlFor="task-minutes"><TextInput id="task-minutes" type="number" required min={1} max={1440} value={spec.minutes} onChange={e => update('minutes', Number(e.target.value))} /></FormField>
          : <FormField label="执行时间" htmlFor="task-time"><TextInput id="task-time" type="time" required value={spec.atTime} onChange={e => update('atTime', e.target.value)} /></FormField>}
        <FormField label="时区" htmlFor="task-zone"><TextInput id="task-zone" required value={spec.timezone} onChange={e => update('timezone', e.target.value)} /></FormField>
        {spec.trigger === 'MONTHLY' && <p className={styles.hint}>每月 1 日发送上一个完整自然月的月报。</p>}
        {spec.trigger === 'WEEKLY' && spec.kind === 'WEEKLY_REPORT' && <p className={styles.hint}>选择一个发送星期；报告统计上一个完整周一至周日。</p>}
        {spec.trigger !== 'MONTHLY' && <div className={styles.checks} aria-label="生效星期">{['一','二','三','四','五','六','日'].map((day, i) => <label key={day} className={styles.check}><input type="checkbox" checked={Boolean(spec.weekdays & (1 << i))} onChange={() => update('weekdays', spec.kind === 'WEEKLY_REPORT' ? (1 << i) : spec.weekdays ^ (1 << i))} />周{day}</label>)}</div>}
        <div className={styles.columns}><FormField label="生效开始" htmlFor="task-start"><TextInput id="task-start" type="time" required value={spec.windowStart} onChange={e => update('windowStart', e.target.value)} /></FormField><FormField label="生效结束" htmlFor="task-end" hint="开始与结束相同表示全天"><TextInput id="task-end" type="time" required value={spec.windowEnd} onChange={e => update('windowEnd', e.target.value)} /></FormField></div>
        {spec.kind === 'SEDENTARY' && <FormField label="空闲多少分钟视为休息" htmlFor="task-break"><TextInput id="task-break" type="number" required min={1} max={120} value={spec.breakMinutes} onChange={e => update('breakMinutes', Number(e.target.value))} /></FormField>}
      </fieldset>
      {spec.kind.endsWith('_REPORT') && <fieldset disabled={busy} className={styles.group}><legend>报告栏目</legend><div className={styles.checks}>{checkbox('expense',spec.kind === 'DAILY_REPORT' ? '支出与资产' : '支出')}{checkbox('todo','待办')}{checkbox('study','学习记录')}</div>{spec.kind === 'DAILY_REPORT' && <p className={styles.hint}>启用后，日报会附上各资产余额、账户收支汇总和当日账单明细。</p>}</fieldset>}
      {spec.kind.endsWith('_REPORT') && <TaskMailTemplateEditor disabled={busy} />}
      <fieldset disabled={busy} className={styles.group}><legend>通知方式</legend><div className={styles.checks}>{checkbox('desktop','桌面通知',spec.kind === 'SEDENTARY')}{checkbox('email','发送邮件')}{checkbox('sound','提醒声音',!spec.desktop)}{checkbox('confirm','需要手动确认',spec.kind === 'SEDENTARY' || !spec.desktop)}</div></fieldset>

      <label className={styles.check}><input type="checkbox" checked={enabled} disabled={busy} onChange={e => { onDirtyChange(true); setEnabled(e.target.checked); }} />保存后启用任务</label>
      <p className={styles.hint}>{scheduleText(spec)}。应用彻底退出后任务停止运行。</p>
      {error && <p role="alert" className={styles.error}>{error}</p>}

      <div className={styles.actions}><Button type="button" variant="outline" disabled={busy} onClick={() => void inspect()}>预览（不发送）</Button><Button type="submit" variant="primary" disabled={busy}>{busy ? '处理中…' : '保存任务'}</Button></div>
    </form>
    {previewOpen && <TaskPreviewModal spec={spec} onClose={() => setPreviewOpen(false)} />}
  </div>;
}
