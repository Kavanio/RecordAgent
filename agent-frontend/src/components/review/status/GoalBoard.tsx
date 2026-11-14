import { useCallback, useEffect, useState } from 'react';
import { Archive, ArchiveRestore, Pencil, Plus, Target } from 'lucide-react';
import { fetchReviewGoals, saveReviewGoal } from '../../../services/reviewApi';
import type { ReviewGoal } from '../../../types/review';
import { Button } from '../../common/Button';
import { FormField } from '../../common/FormField';
import { useMessage } from '../../common/Message';
import { TextInput } from '../../common/TextInput';
import { ReviewFormModal } from '../ReviewFormModal';
import { StatusCard } from './StatusCard';
import styles from './GoalBoard.module.css';

const MAX_ACTIVE = 3;
interface GoalDraft { id?: string; revision: number; title: string; constraint: string; criterion: string; state: ReviewGoal['state'] }
const EMPTY_DRAFT: GoalDraft = { revision: 0, title: '', constraint: '', criterion: '', state: 'ACTIVE' };

/**
 * 个人目标看板：最多 3 个有效目标（后端强制），支持新增、编辑、归档与恢复。
 * 目标只由用户维护，模型只能引用；生活复盘 GOALS 来源与主动候选都读取这里。
 */
export function GoalBoard() {
  const [goals, setGoals] = useState<ReviewGoal[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [draft, setDraft] = useState<GoalDraft | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const { showMessage } = useMessage();

  const load = useCallback(async () => {
    setError('');
    try { setGoals(await fetchReviewGoals()); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '目标读取失败'); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  async function save(goal: GoalDraft, success: string) {
    if (busy || !goal.title.trim()) return;
    setBusy(true);
    try {
      await saveReviewGoal({ expectedRevision: goal.revision, title: goal.title.trim(), constraintText: goal.constraint.trim(),
        successCriterion: goal.criterion.trim(), state: goal.state }, goal.id);
      showMessage('success', success);
      setDraft(null);
      await load();
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '操作失败');
    } finally { setBusy(false); }
  }
  const toDraft = (goal: ReviewGoal, state = goal.state): GoalDraft => ({ id: goal.id, revision: goal.revision, title: goal.title,
    constraint: goal.constraintText, criterion: goal.successCriterion, state });

  const active = goals.filter(goal => goal.state === 'ACTIVE');
  const archived = goals.filter(goal => goal.state !== 'ACTIVE');
  const freeSlots = Math.max(0, MAX_ACTIVE - active.length);

  return <StatusCard
    title="个人目标"
    icon={<Target size={16} />}
    badge={<span className={styles.count} title={`${active.length}/${MAX_ACTIVE} 个进行中`}>{active.length}/{MAX_ACTIVE}</span>}
    actions={<Button size="sm" variant="ghost" icon={<Plus size={14} />} disabled={busy || freeSlots === 0}
      onClick={() => setDraft({ ...EMPTY_DRAFT })}>新增目标</Button>}
  >
    {error && <p role="alert" className={styles.error}>{error}<Button size="sm" onClick={() => void load()}>重新读取</Button></p>}
    {!loading && active.length === 0 && !error && (
      <div className={styles.empty}>
        <strong>还没有设定目标</strong>
        <p>设定后将在复盘与建议中被引用。</p>
      </div>
    )}
    {active.length > 0 && <ul className={styles.goals}>
      {active.map((goal, index) => <li key={goal.id} className={styles.goal}>
        <span className={styles.index} aria-hidden="true">0{index + 1}</span>
        <div className={styles.goalBody}>
          <strong>{goal.title}</strong>
          {goal.successCriterion && <p className={styles.criterion}>{goal.successCriterion}</p>}
          {goal.constraintText && <p className={styles.constraint}><span>边界</span>{goal.constraintText}</p>}
        </div>
        <div className={styles.goalActions}>
          <Button size="sm" variant="ghost" iconOnly icon={<Pencil size={14} />} aria-label={`编辑目标 ${goal.title}`} disabled={busy}
            onClick={() => setDraft(toDraft(goal))}>{''}</Button>
          <Button size="sm" variant="ghost" iconOnly icon={<Archive size={14} />} aria-label={`归档目标 ${goal.title}`} disabled={busy}
            onClick={() => void save(toDraft(goal, 'ARCHIVED'), '目标已归档')}>{''}</Button>
        </div>
      </li>)}
    </ul>}
    {archived.length > 0 && <div className={styles.archived}>
      <button type="button" className={styles.archivedToggle} aria-expanded={showArchived} onClick={() => setShowArchived(v => !v)}>
        已归档 {archived.length}{showArchived ? ' ▴' : ' ▾'}
      </button>
      {showArchived && <ul>
        {archived.map(goal => <li key={goal.id}>
          <span>{goal.title}</span>
          <Button size="sm" variant="ghost" icon={<ArchiveRestore size={14} />} disabled={busy || freeSlots === 0}
            title={freeSlots === 0 ? '进行中的目标已满 3 个，请先归档一个' : undefined}
            onClick={() => void save(toDraft(goal, 'ACTIVE'), '目标已恢复')}>恢复</Button>
        </li>)}
      </ul>}
    </div>}

    <ReviewFormModal open={draft !== null} title={draft?.id ? '编辑目标' : '新增目标'} busy={busy} onClose={() => setDraft(null)} footer={<>
      <Button size="sm" onClick={() => setDraft(null)}>取消</Button>
      <Button variant="primary" size="sm" disabled={busy || !draft?.title.trim()}
        onClick={() => draft && void save(draft, draft.id ? '目标已更新' : '目标已保存')}>{draft?.id ? '保存修改' : '添加目标'}</Button>
    </>}>
      {draft && <div className={styles.form}>
        <FormField label="目标内容" htmlFor="life-goal-title" required>
          <TextInput id="life-goal-title" aria-label="目标名称" value={draft.title} maxLength={200} placeholder="例如：每周完成三次学习" disabled={busy}
            onChange={event => setDraft({ ...draft, title: event.target.value })} />
        </FormField>
        <FormField label="执行约束" htmlFor="life-goal-constraint" hint="可留空，例如不牺牲睡眠。">
          <TextInput id="life-goal-constraint" aria-label="目标约束" value={draft.constraint} maxLength={500} placeholder="填写你希望遵守的边界" disabled={busy}
            onChange={event => setDraft({ ...draft, constraint: event.target.value })} />
        </FormField>
        <FormField label="进展标准" htmlFor="life-goal-criterion" hint="可留空，帮助判断是否有进展。">
          <TextInput id="life-goal-criterion" aria-label="目标衡量方式" value={draft.criterion} maxLength={300} placeholder="例如：每周至少读书 3 次" disabled={busy}
            onChange={event => setDraft({ ...draft, criterion: event.target.value })} />
        </FormField>
      </div>}
    </ReviewFormModal>
  </StatusCard>;
}
