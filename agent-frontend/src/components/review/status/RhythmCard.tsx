import { useEffect, useId, useState } from 'react';
import { Clock3 } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeCognition, LifeHabit, LifeHabitSuggestion } from '../../../types/lifeReview';
import {
  busySegments, formatHour, formatRhythmSummary, rhythmBars, rhythmWaveGeometry, zonedClock,
} from '../../../features/review/statusPresentation';
import { Button } from '../../common/Button';
import { Select } from '../../common/Select';
import { TextInput } from '../../common/TextInput';
import { ReviewFormModal } from '../ReviewFormModal';
import styles from './RhythmCard.module.css';

const HOUR_OPTIONS = Array.from({ length: 12 }, (_, idx) => {
  const h = idx + 12;
  return { value: String(h), label: `每天 ${h}:00${h === 21 ? '（默认）' : ''}` };
});
const SUGGESTION_SOURCE = '采用近 14 天互动活跃时段建议';
const AXIS_HOURS = [0, 6, 12, 18, 23] as const;

interface RhythmCardProps {
  timezone?: string;
  habit: LifeHabit | null;
  suggestion: LifeHabitSuggestion | null;
  cognition: LifeCognition | null;
  busy: boolean;
  disabled: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 活态 24h 作息节律波：平滑活动波形、呼吸实时时刻信标、忙碌雾带与悬停微交互。 */
export function RhythmCard({ timezone = 'Asia/Shanghai', habit, suggestion, cognition, busy, disabled, act }: RhythmCardProps) {
  const uid = useId().replace(/:/g, '');
  const [modalOpen, setModalOpen] = useState(false);
  const [hour, setHour] = useState(21);
  const [note, setNote] = useState('');
  const [hoverHour, setHoverHour] = useState<number | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 30000);
    return () => window.clearInterval(timer);
  }, []);

  const current = habit?.active ? habit.availableHour : 21;
  const suggested = suggestion?.available ? suggestion.suggestedHour : null;
  const bars = rhythmBars(suggestion?.hourCounts, cognition?.busyStartHour, cognition?.busyEndHour);
  const wave = rhythmWaveGeometry(bars);
  const mist = busySegments(cognition?.busyStartHour, cognition?.busyEndHour);
  const hovered = hoverHour === null ? null : bars[hoverHour] ?? null;
  const summary = formatRhythmSummary(suggestion);
  const nowClock = zonedClock(timezone, new Date(nowMs));
  const nowEdge = nowClock.fraction < 0.12 ? 'left' : nowClock.fraction > 0.88 ? 'right' : undefined;

  function openModal(initialHour = current, initialNote = habit?.source || '') {
    setHour(initialHour); setNote(initialNote); setModalOpen(true);
  }

  return <section className={styles.section} aria-label="作息节律">
    <header className={styles.header}>
      <div className={styles.titleRow}>
        <span className={styles.title}><Clock3 size={14} aria-hidden="true" />今日节律</span>
        <span className={styles.nowChip} title="当前配置时区时刻"><i aria-hidden="true" />{nowClock.label}</span>
      </div>
      <div className={styles.actions}>
        {suggested !== null && suggested !== current && (
          <Button size="sm" variant="ghost" disabled={disabled}
            onClick={() => void act(() => lifeReviewApi.saveHabit(habit?.revision || 0, suggested, SUGGESTION_SOURCE, true), `已采用建议时间：每天 ${suggested}:00`)}>
            采用建议 {suggested}:00
          </Button>
        )}
        <Button size="sm" variant="ghost" disabled={disabled}
          aria-label={`设置每日时间（${formatHour(current)}）`} onClick={() => openModal()}>
          每日 {formatHour(current)}
        </Button>
      </div>
    </header>

    <div className={styles.readout}>
      {hovered ? (
        <span className={styles.hoverInfo}>
          <strong>{formatHour(hovered.hour)}</strong>
          <span>{hovered.count > 0 ? `${hovered.count} 次互动` : '无互动'}</span>
          {hovered.busy && <span>· 忙碌时段</span>}
          {hovered.hour === current && <span>· 复盘时间</span>}
          {hovered.hour >= 12 && hovered.hour <= 23 && !disabled && <span className={styles.clickHint}>· 点按设此时</span>}
        </span>
      ) : (
        <span className={styles.summary} title={summary.detail}>
          {cognition ? `忙碌 ${formatHour(cognition.busyStartHour)}—${formatHour(cognition.busyEndHour)} · ` : ''}
          {summary.text}
        </span>
      )}
    </div>

    <div className={styles.trackArea}>
      {hovered && (
        <div
          className={styles.floatTip}
          style={{ left: `${((hovered.hour + 0.5) / 24) * 100}%` }}
          data-edge={hovered.hour <= 2 ? 'left' : hovered.hour >= 21 ? 'right' : undefined}
          aria-hidden="true"
        >
          {formatHour(hovered.hour)} · {hovered.count} 次
        </div>
      )}

      <div className={styles.waveBox} role="img" aria-label={wave.hasData ? '近 14 天各小时互动波动与实时时刻' : '暂无足够互动记录的 24 小时基线'}>
        {mist.map((seg, i) => (
          <div key={i} className={styles.busyMist} style={{ left: `${seg.startPct}%`, width: `${seg.widthPct}%` }} aria-hidden="true" />
        ))}
        <div className={styles.livedWash} style={{ width: `${nowClock.fraction * 100}%` }} aria-hidden="true" />

        <svg className={styles.waveSvg} viewBox="0 0 240 54" preserveAspectRatio="none" aria-hidden="true">
          <defs>
            <linearGradient id={`waveFill-${uid}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#3b82f6" stopOpacity={wave.hasData ? '0.26' : '0.12'} />
              <stop offset="100%" stopColor="#3b82f6" stopOpacity="0.01" />
            </linearGradient>
          </defs>
          <line x1="0" y1="50" x2="240" y2="50" className={styles.baseAxisLine} />
          <path d={wave.areaPath} fill={`url(#waveFill-${uid})`} className={styles.waveArea} />
          <path d={wave.strokePath} className={wave.hasData ? styles.waveStroke : styles.waveAmbient} />
        </svg>

        <div className={styles.nowMarker} style={{ left: `${nowClock.fraction * 100}%` }} data-edge={nowEdge} aria-hidden="true">
          <span className={styles.nowDot} />
        </div>

        <div className={styles.stems}>
          {bars.map((bar, idx) => {
            const selectable = bar.hour >= 12 && bar.hour <= 23 && !disabled;
            const isCurrent = bar.hour === current;
            const isHovered = hoverHour === bar.hour;
            const topPct = (wave.points[idx].y / 54) * 100;
            const stemHeightPct = Math.max(0, ((50 - wave.points[idx].y) / 54) * 100);
            return <button key={bar.hour} type="button" className={styles.bar} tabIndex={selectable ? 0 : -1}
              data-active={bar.count > 0 || undefined}
              data-hovered={isHovered || undefined}
              data-current={isCurrent || undefined}
              data-suggested={bar.hour === suggested || undefined}
              disabled={!selectable}
              aria-label={`设为每天 ${bar.hour}:00 复盘`}
              onMouseEnter={() => setHoverHour(bar.hour)}
              onMouseLeave={() => setHoverHour(null)}
              onFocus={() => setHoverHour(bar.hour)}
              onBlur={() => setHoverHour(null)}
              onClick={() => openModal(bar.hour, bar.hour === suggested ? SUGGESTION_SOURCE : '')}>
              {(bar.count > 0 || isHovered) && (
                <>
                  <i className={styles.stemLine} style={{ height: `${stemHeightPct}%` }} aria-hidden="true" />
                  <i className={styles.peakDot} style={{ top: `${topPct}%` }} aria-hidden="true" />
                </>
              )}
              {isCurrent && (
                <>
                  <i className={styles.reviewDot} aria-hidden="true" />
                  <em className={styles.reviewPin} data-edge={bar.hour >= 21 ? 'right' : undefined} aria-hidden="true">{bar.hour}时复盘</em>
                </>
              )}
            </button>;
          })}
        </div>
      </div>

      <div className={styles.axis} aria-hidden="true">
        {AXIS_HOURS.map(h => <span key={h}>{h}时</span>)}
      </div>
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
  </section>;
}
