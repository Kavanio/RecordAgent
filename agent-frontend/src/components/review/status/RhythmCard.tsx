import { useState } from 'react';
import { Clock3 } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeCognition, LifeHabit, LifeHabitSuggestion } from '../../../types/lifeReview';
import { formatHour, rhythmBars } from '../../../features/review/statusPresentation';
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

/** 作息节律：近 14 天互动小时分布 + 忙碌时段阴影 + 当前复盘时间；可点击柱子设定复盘时间。 */
export function RhythmCard({ habit, suggestion, cognition, busy, disabled, act }: RhythmCardProps) {
  const [modalOpen, setModalOpen] = useState(false);
  const [hour, setHour] = useState(21);
  const [note, setNote] = useState('');
  const current = habit?.active ? habit.availableHour : 21;
  const suggested = suggestion?.available ? suggestion.suggestedHour : null;
  const bars = rhythmBars(suggestion?.hourCounts, cognition?.busyStartHour, cognition?.busyEndHour);
  const hasData = bars.some(bar => bar.count > 0);

  function openModal(initialHour = current, initialNote = habit?.source || '') {
    setHour(initialHour); setNote(initialNote); setModalOpen(true);
  }

  return <StatusCard
    title="作息节律"
    icon={<Clock3 size={16} />}
    description={`每日复盘 ${formatHour(current)}`}
    actions={<Button size="sm" variant="outline" disabled={disabled} onClick={() => openModal()}>{`设置每日时间（${formatHour(current).replace(/^0/, '')}）`}</Button>}
  >
    <div className={styles.chart} role="img" aria-label={hasData ? '近 14 天各小时互动次数分布' : '暂无足够的互动时间数据'}>
      {bars.map(bar => {
        const selectable = bar.hour >= 12 && bar.hour <= 23 && !disabled;
        return <button key={bar.hour} type="button" className={styles.bar} tabIndex={selectable ? 0 : -1}
          data-busy={bar.busy || undefined} data-current={bar.hour === current || undefined} data-suggested={bar.hour === suggested || undefined}
          disabled={!selectable} aria-label={`设为每天 ${bar.hour}:00 复盘`}
          title={`${formatHour(bar.hour)} · ${bar.count} 次互动${bar.busy ? ' · 忙碌时段' : ''}`}
          onClick={() => openModal(bar.hour, bar.hour === suggested ? SUGGESTION_SOURCE : '')}>
          <span style={{ height: `${Math.max(bar.ratio * 100, bar.count > 0 ? 8 : 3)}%` }} />
        </button>;
      })}
    </div>
    <div className={styles.axis} aria-hidden="true"><span>0时</span><span>6时</span><span>12时</span><span>18时</span><span>23时</span></div>
    <div className={styles.legend}>
      <span><i className={styles.legendActive} />互动分布</span>
      {cognition && <span><i className={styles.legendBusy} />忙碌 {formatHour(cognition.busyStartHour)}—{formatHour(cognition.busyEndHour)}</span>}
      <span><i className={styles.legendCurrent} />复盘时间</span>
    </div>
    <p className={styles.note} title="仅统计近 14 天普通会话的消息时间戳（不读正文、不调用模型），至少 3 个活跃日与 5 次互动后给出建议。">
      {suggestion?.note || '近 14 天暂未积累足够互动时段样本，点柱子可手动调整时间。'}
    </p>
    {suggested !== null && suggested !== current && (
      <Button size="sm" variant="outline" disabled={disabled}
        onClick={() => void act(() => lifeReviewApi.saveHabit(habit?.revision || 0, suggested, SUGGESTION_SOURCE, true), `已采用建议时间：每天 ${suggested}:00`)}>
        采用建议（{suggested}:00）
      </Button>
    )}

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
