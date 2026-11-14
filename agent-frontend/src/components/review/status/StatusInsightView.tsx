import { useState } from 'react';
import { Brain, Clock, Compass, Heart, MessageCircleHeart, Pencil, RefreshCw } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeCompanionView, SaveLifeCognitionInput } from '../../../types/lifeReview';
import { formatDateTime, formatHour } from '../../../features/review/statusPresentation';
import { Button } from '../../common/Button';
import { Select } from '../../common/Select';
import { MemoryDocumentManager } from '../../memory/MemoryDocumentManager';
import { PersonalProfileSection } from '../../settings/PersonalProfileSection';
import { ReviewEmptyState } from '../ReviewEmptyState';
import { ReviewFormModal } from '../ReviewFormModal';
import styles from './StatusInsightView.module.css';

type CognitionDimensionKey = 'backgroundAndPreferences' | 'routineAndBusyHours' | 'activeFocusAndBlockers' | 'pendingHooksAndCuriosities';

const DIMENSIONS: readonly {
  key: CognitionDimensionKey;
  title: string;
  placeholder: string;
  fallback: string;
  icon: typeof Brain;
}[] = [
  { key: 'backgroundAndPreferences', title: '基本背景与性格喜好', fallback: '等待从日常对话与记录中自动学习…', placeholder: '例如：偏好简洁、直接给结论的中文交流', icon: Heart },
  { key: 'routineAndBusyHours', title: '作息规律与平时忙闲时段', fallback: '工作日白天通常在忙，晚间较常互动', placeholder: '例如：工作日 09:00—18:00 专心工作，晚间活跃', icon: Clock },
  { key: 'activeFocusAndBlockers', title: '近期主线大事与卡点', fallback: '等待从待办、学习与手记中自动提炼近期主线…', placeholder: '例如：正在推进某个项目或需要突破的难题', icon: Compass },
  { key: 'pendingHooksAndCuriosities', title: '上次未完话头与好奇点', fallback: '等待从最近聊天中捕捉待跟进话头与好奇提问…', placeholder: '例如：上次聊到但还没来得及继续的话题', icon: MessageCircleHeart },
];

const HOURS = Array.from({ length: 24 }, (_, idx) => ({ value: String(idx), label: formatHour(idx) }));

interface StatusInsightViewProps {
  companion: LifeCompanionView | null;
  timezone: string;
  busy: boolean;
  memorySummary: { active: number; total: number } | null;
  onPendingProposalChange?: (hasPending: boolean) => void;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 「认知」分段：对比展示 Agent 自动学习的四维系统性认知与用户主动维护的画像/经历来源。 */
export function StatusInsightView({ companion, timezone, busy, memorySummary, onPendingProposalChange, act }: StatusInsightViewProps) {
  const [editing, setEditing] = useState<CognitionDimensionKey | null>(null);
  const [draft, setDraft] = useState<SaveLifeCognitionInput | null>(null);
  const cognition = companion?.cognition ?? null;

  function openEdit(key: CognitionDimensionKey) {
    if (!cognition) return;
    setDraft({
      backgroundAndPreferences: cognition.backgroundAndPreferences,
      routineAndBusyHours: cognition.routineAndBusyHours,
      activeFocusAndBlockers: cognition.activeFocusAndBlockers,
      pendingHooksAndCuriosities: cognition.pendingHooksAndCuriosities,
      busyStartHour: cognition.busyStartHour,
      busyEndHour: cognition.busyEndHour,
    });
    setEditing(key);
  }

  const activeDim = DIMENSIONS.find(item => item.key === editing) ?? null;

  return <div className={styles.page} role="tabpanel" id="status-panel-insight" aria-labelledby="status-tab-insight">
    <section className={styles.block} aria-label="Agent 对你的系统性认知">
      <header className={styles.blockHeader}>
        <div>
          <h2><Brain size={16} aria-hidden="true" />Agent 学到的你</h2>
          <p>更新于 {formatDateTime(cognition?.updatedAt, timezone)}</p>
        </div>
        {companion && (
          <Button size="sm" variant="outline" icon={<RefreshCw size={14} />} disabled={busy}
            onClick={() => void act(() => lifeReviewApi.learnCompanion(), '已从近期全量记录更新对你的系统性认知')}>
            更新认知
          </Button>
        )}
      </header>

      {!cognition ? (
        <div className={styles.emptyCard}>
          <ReviewEmptyState title="尚未获取到系统性认知" description="等待后台陪伴模块初始化完成后，这里会展示自动学习到的四个维度。" />
        </div>
      ) : (
        <div className={styles.grid}>
          {DIMENSIONS.map(dim => {
            const Icon = dim.icon;
            const text = cognition[dim.key];
            return <article key={dim.key} className={styles.dimCard}>
              <div className={styles.dimHeader}>
                <span className={styles.dimTitle}><Icon size={15} aria-hidden="true" />{dim.title}</span>
                <Button size="sm" variant="ghost" icon={<Pencil size={13} />} disabled={busy}
                  aria-label={`修改 ${dim.title}`} onClick={() => openEdit(dim.key)}>
                  修改
                </Button>
              </div>
              <p className={text ? styles.dimText : styles.dimFallback}>{text || dim.fallback}</p>
              {dim.key === 'routineAndBusyHours' && (
                <div className={styles.busyWindow}>
                  免打扰 {formatHour(cognition.busyStartHour)}—{formatHour(cognition.busyEndHour)}
                </div>
              )}
            </article>;
          })}
        </div>
      )}
    </section>

    <section className={styles.block} aria-label="由你直接维护的上下文">
      <header className={styles.blockHeader}>
        <div>
          <h2>你告诉 Agent 的</h2>
        </div>
      </header>
      <div className={styles.tiles}>
        <PersonalProfileSection showMaintenanceToggle={false} variant="tile" onPendingProposalChange={onPendingProposalChange} />
        <MemoryDocumentManager variant="tile" sourceSummary={memorySummary} />
      </div>
    </section>

    <ReviewFormModal
      open={Boolean(activeDim && draft)}
      title={activeDim ? `修改 · ${activeDim.title}` : '修改系统性认知'}
      busy={busy}
      onClose={() => { setEditing(null); setDraft(null); }}
      footer={<>
        <Button size="sm" onClick={() => { setEditing(null); setDraft(null); }}>取消</Button>
        <Button size="sm" variant="primary" disabled={busy || !draft} onClick={() => draft && void act(async () => {
          await lifeReviewApi.saveCognition(draft);
          setEditing(null);
          setDraft(null);
        }, '系统性认知已保存')}>
          保存系统性认知
        </Button>
      </>}
    >
      {activeDim && draft && <div className={styles.modalForm}>
        <label className={styles.field}>
          <span>{activeDim.title}</span>
          <textarea
            className={styles.textarea}
            aria-label={activeDim.title}
            rows={4}
            value={draft[activeDim.key]}
            placeholder={activeDim.placeholder}
            disabled={busy}
            onChange={event => setDraft({ ...draft, [activeDim.key]: event.target.value })}
          />
        </label>
        {activeDim.key === 'routineAndBusyHours' && (
          <div className={styles.field}>
            <span>常规忙碌时段（忙时未回复将顺延至忙完后跟进）</span>
            <div className={styles.hours}>
              <Select aria-label="忙碌开始小时" value={String(draft.busyStartHour)} disabled={busy} options={HOURS}
                onChange={event => setDraft({ ...draft, busyStartHour: Number(event.target.value) })} />
              <span>至</span>
              <Select aria-label="忙碌结束小时" value={String(draft.busyEndHour)} disabled={busy} options={HOURS}
                onChange={event => setDraft({ ...draft, busyEndHour: Number(event.target.value) })} />
            </div>
          </div>
        )}
      </div>}
    </ReviewFormModal>
  </div>;
}
