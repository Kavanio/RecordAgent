import { useCallback, useEffect, useState } from 'react';
import { Plus } from 'lucide-react';
import type { ReviewGoal } from '../../types/review';
import { fetchReviewGoals, saveReviewGoal } from '../../services/reviewApi';
import { STATE_TEXT } from '../../features/review/statisticsReviewPresentation';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { Button } from '../common/Button';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import { useMessage } from '../common/Message';
import { ReviewEmptyState } from './ReviewEmptyState';
import { ReviewFormModal } from './ReviewFormModal';
import styles from './LifeReviewSettings.module.css';

/**
 * 个人目标只在生活复盘里维护：目标既作为生活复盘来源，也被主动候选引用。
 * 模型只能引用，不能改写；最多 3 个有效目标由后端强制。
 */
export function LifeGoalSettings() {
  const [goals, setGoals] = useState<ReviewGoal[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState('');
  const [constraint, setConstraint] = useState('');
  const [criterion, setCriterion] = useState('');
  const { showMessage } = useMessage();

  const load = useCallback(async () => {
    setError('');
    try { setGoals(await fetchReviewGoals()); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '目标读取失败'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function act(work: () => Promise<unknown>, success: string) {
    if (busy) return;
    setBusy(true);
    try { await work(); showMessage('success', success); await load(); }
    catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '操作失败'); }
    finally { setBusy(false); }
  }
  function add() {
    if (!title.trim()) return;
    void act(async () => {
      await saveReviewGoal({ expectedRevision: 0, title: title.trim(), constraintText: constraint.trim(),
        successCriterion: criterion.trim(), state: 'ACTIVE' });
      setTitle(''); setConstraint(''); setCriterion(''); setOpen(false);
    }, '目标已保存');
  }
  function archive(goal: ReviewGoal) {
    void act(() => saveReviewGoal({ expectedRevision: goal.revision, title: goal.title,
      constraintText: goal.constraintText, successCriterion: goal.successCriterion, state: 'ARCHIVED' }, goal.id), '目标已归档');
  }

  return <SettingsGroup title="个人目标" description="只由你维护，模型只能引用不能改写；最多 3 个有效目标。生活复盘会把它当作来源之一。">
    {!loading && goals.length === 0 && <ReviewEmptyState title="还没有设定目标" description="写下你想达成的事和边界，复盘时会作为约束引用，不会自动变成待办。" />}
    {goals.map(goal => <SettingsRow key={goal.id} label={goal.title}
      description={`${goal.constraintText || '无额外约束'} · ${goal.successCriterion || '未填写衡量方式'} · ${STATE_TEXT[goal.state] ?? goal.state}`}
      control={goal.state === 'ACTIVE'
        ? <Button variant="ghost" size="sm" disabled={busy} onClick={() => archive(goal)}>归档</Button>
        : <span className={styles.muted}>已归档</span>} />)}
    {error && <p role="alert" className={styles.error}>{error}<Button size="sm" onClick={() => void load()}>重新读取</Button></p>}
    <SettingsRow label="添加目标" description="由你明确填写，不由模型自动创建。"
      control={<Button size="sm" disabled={busy} onClick={() => { setTitle(''); setConstraint(''); setCriterion(''); setOpen(true); }}>新增目标</Button>} />
    <ReviewFormModal open={open} title="新增目标" busy={busy} onClose={() => setOpen(false)} footer={<>
      <Button size="sm" onClick={() => setOpen(false)}>取消</Button>
      <Button variant="primary" size="sm" disabled={busy || !title.trim()} onClick={add}><Plus size={14} />添加目标</Button>
    </>}>
      <div className={styles.stack}>
        <FormField label="目标内容" htmlFor="life-goal-title" required>
          <TextInput id="life-goal-title" aria-label="目标名称" value={title} maxLength={200} placeholder="例如：每周完成三次学习" disabled={busy}
            onChange={event => setTitle(event.target.value)} />
        </FormField>
        <FormField label="执行约束" htmlFor="life-goal-constraint" hint="可留空，例如不牺牲睡眠。">
          <TextInput id="life-goal-constraint" aria-label="目标约束" value={constraint} maxLength={500} placeholder="填写你希望遵守的边界" disabled={busy}
            onChange={event => setConstraint(event.target.value)} />
        </FormField>
        <FormField label="进展标准" htmlFor="life-goal-criterion" hint="可留空，帮助判断是否有进展。">
          <TextInput id="life-goal-criterion" aria-label="目标衡量方式" value={criterion} maxLength={300} placeholder="例如：每周至少读书 3 次" disabled={busy}
            onChange={event => setCriterion(event.target.value)} />
        </FormField>
      </div>
    </ReviewFormModal>
  </SettingsGroup>;
}
