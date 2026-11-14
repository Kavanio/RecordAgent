import { useState } from 'react';
import { Clock3 } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeCognition, LifeHabit, LifeHabitSuggestion } from '../../../types/lifeReview';
import { formatHour, formatRhythmSummary, rhythmBars } from '../../../features/review/statusPresentation';
import { Button } from '../../common/Button';
import { Select } from '../../common/Select';
import { TextInput } from '../../common/TextInput';
import { ReviewFormModal } from '../ReviewFormModal';
import { StatusCard } from './StatusCard';
import styles from './RhythmCard.module.css';

const HOUR_OPTIONS = Array.from({ length: 12 }, (_, idx) => {
  const h = idx + 12;
  return { value: String(h), label: `每天 ${h}:00${h === 21 ? '（默认）' : ''}` };
});
const SUGGESTION_SOURCE = '采用近 14 天互动活跃时段建议';

interface RhythmCardProps {
  habit: LifeHabit | null;
  suggestion: LifeHabitSuggestion | null;
  cognition: LifeCognition | null;
  busy: boolean;
  disabled: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 作息节律：静默忙碌底带 + 清晰活跃柱形 + 复盘时间定位针；悬停实时读取小时数据。 */
export function RhythmCard({ habit, suggestion, cognition, busy, disabled, act }: RhythmCardProps) {
  const [modalOpen, setModalOpen] = useState(false);
  const [hour, setHour] = useState(21);
  const [note, setNote] = useState('');
  const [hoverHour, setHoverHour] = useState<number | null>(null);
  const current = habit?.active ? habit.availableHour : 21;
  const suggested = suggestion?.available ? suggestion.suggestedHour : null;
  const bars = rhythmBars(suggestion?.hourCounts, cognition?.busyStartHour, cognition?.busyEndHour);
  const hasData = bars.some(bar => bar.count > 0);
  const hovered = hoverHour === null ? null : bars[hoverHour] ?? null;
  const summary = formatRhythmSummary(suggestion);

  function openModal(initialHour = current, initialNote = habit?.source || '') {
    setHour(initialHour); setNote(initialNote); setModalOpen(true);
  }

  return <StatusCard
    title="作息节律"
    icon={<Clock3 size={16} />}
    actions={<Button size="sm" variant="outline" disabled={disabled}
      aria-label={`设置每日时间（${formatHour(current)}）`} onClick={() => openModal()}>
      每日 {formatHour(current)}
    </Button>}
  >
    <div className={styles.chartWrap}>
      <div className={styles.chart} role="img" aria-label={hasData ? '近 14 天各小时互动次数分布' : '暂无足够的互动时间数据'}>
        {bars.map(bar => {
          const selectable = bar.hour >= 12 && bar.hour <= 23 && !disabled;
          const isCurrent = bar.hour === current;
          const height = bar.count > 0 ? `${Math.max(bar.ratio * 100, 18)}%` : '2px';
          return <button key={bar.hour} type="button" className={styles.bar} tabIndex={selectable ? 0 : -1}
            data-busy={bar.busy || undefined}
            data-empty={bar.count === 0 || undefined}
            data-current={isCurrent || undefined}
            data-suggested={bar.hour === suggested || undefined}
            disabled={!selectable}
            aria-label={`设为每天 ${bar.hour}:00 复盘`}
            onMouseEnter={() => setHoverHour(bar.hour)}
            onMouseLeave={() => setHoverHour(null)}
            onFocus={() => setHoverHour(bar.hour)}
            onBlur={() => setHoverHour(null)}
            onClick={() => openModal(bar.hour, bar.hour === suggested ? SUGGESTION_SOURCE : '')}>
            {isCurrent && <em className={styles.currentPin} aria-hidden="true">{bar.hour}时</em>}
            <span style={{ height }} />
          </button>;
        })}
      </div>
      <div className={styles.axis} aria-hidden="true"><span>0时</span><span>6时</span><span>12时</span><span>18时</span><span>23时</span></div>
    </div>

    <div className={styles.readout}>
      {hovered ? (
        <span className={styles.hoverInfo}>
          <strong>{formatHour(hovered.hour)}</strong>
          <span>{hovered.count > 0 ? `${hovered.count} 次互动` : '无互动记录'}</span>
          {hovered.busy && <span>· 忙碌时段</span>}
          {hovered.hour === current && <span>· 复盘时间</span>}
        </span>
      ) : (
        <div className={styles.legend}>
          <span><i className={styles.legendActive} />互动分布</span>
          {cognition && <span><i className={styles.legendBusy} />忙碌 {formatHour(cognition.busyStartHour)}—{formatHour(cognition.busyEndHour)}</span>}
          <span><i className={styles.legendCurrent} />复盘 {formatHour(current)}</span>
        </div>
      )}
    </div>

    <div className={styles.footRow}>
      <p className={styles.note} title={summary.detail}>{summary.text}</p>
      {suggested !== null && suggested !== current && (
        <Button size="sm" variant="outline" disabled={disabled}
          onClick={() => void act(() => lifeReviewApi.saveHabit(habit?.revision || 0, suggested, SUGGESTION_SOURCE, true), `已采用建议时间：每天 ${suggested}:00`)}>
          采用建议 {suggested}:00
        </Button>
      )}
    </div>

    <ReviewFormModal open={modalOpen} title="设置每日复盘与发送时间" busy={busy} onClose={() => setModalOpen(false)} footer={<>
      {habit?.active && <Button size="sm" disabled={disabled} onClick={() => void act(async () => {
        await lifeReviewApi.saveHabit(habit.revision, 21, '恢复默认 21:00', false);
        setModalOpen(false);
      }, '已恢复默认每天 21:00')}>恢复默认 21:00</Button>}
      <Button size="sm" variant="primary" disabled={disabled} onClick={() => void act(async () => {
        await lifeReviewApi.saveHabit(habit?.revision || 0, hour, note, true);
        setModalOpen(false);
      }, `已设为每天 ${hour}:00 自动复盘`)}>保存时间</Button>
    </>}>
      <div className={styles.form}>
        <p className={styles.note}>到达设定整点后自动准备今日复盘；若开启了主动呈现与飞书投递，生成后会自动发送。作息只在你确认后生效，没有互动不代表没起床或身体异常。</p>
        <Select aria-label="生活复盘作息小时" value={String(hour)} disabled={disabled} options={HOUR_OPTIONS}
          onChange={event => setHour(Number(event.target.value))} />
        <TextInput aria-label="生活复盘作息来源" value={note} maxLength={300} disabled={disabled}
          placeholder="备注说明（可选，不填可直接保存）" onChange={event => setNote(event.target.value)} />
      </div>
    </ReviewFormModal>
  </StatusCard>;
}
