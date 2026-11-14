import { useState } from 'react';
import { CalendarCheck, ChevronDown, Info, RefreshCw, Sparkles } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeContent, LifeHabit, LifeSettings } from '../../../types/lifeReview';
import { dayTitle, formatHour, sortVersions } from '../../../features/review/statusPresentation';
import { Button } from '../../common/Button';
import { ConfirmDialog } from '../../common/ConfirmDialog';
import { LifeContentCard } from '../LifeContentCard';
import { ReviewEmptyState } from '../ReviewEmptyState';
import { StatusCard } from './StatusCard';
import styles from './TodayReviewHero.module.css';

interface TodayReviewHeroProps {
  settings: LifeSettings;
  history: LifeContent[];
  habit: LifeHabit | null;
  today: string;
  busy: boolean;
  generating: boolean;
  disabled: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
  onOpenSettings?: (tab?: 'review' | 'context') => void;
}

/** 今日复盘主卡：只平铺今日内容或最近有效回顾；旧日未生成仅保留紧凑折叠条，避免大片报错占满首屏。 */
export function TodayReviewHero({ settings: s, history, habit, today, busy, generating, disabled, act, onOpenSettings }: TodayReviewHeroProps) {
  const [refreshOpen, setRefreshOpen] = useState(false);
  const [expandFailedPrev, setExpandFailedPrev] = useState(false);
  const ordered = sortVersions(history);
  const todayContent = ordered.find(c => c.day === today);
  const latest = ordered[0];
  const latestIsFailedPast = Boolean(!todayContent && latest && latest.items.length === 0 && latest.state !== 'PENDING' && latest.state !== 'RUNNING');
  const shown = todayContent ?? (latestIsFailedPast ? null : latest);
  const hour = habit?.active ? habit.availableHour : 21;
  const blocked = !s.enabled || !s.authorized;

  function prepare() {
    const readyToday = history.some(c => c.day === today && c.state === 'READY');
    void act(() => lifeReviewApi.prepare(today, 0, false),
      readyToday ? '正在查看今天已有版本；需要重新采集资料时请点“更新今天的复盘”' : '已开始后台生成今日复盘，请稍候…');
  }

  return <StatusCard
    title="今天的复盘"
    tone="accent"
    icon={<CalendarCheck size={16} />}
    description={`每日 ${formatHour(hour)}${s.feishu ? ' · 同步飞书' : ''}`}
    actions={<>
      <Button size="sm" variant="primary" icon={<Sparkles size={14} />} disabled={disabled || generating} onClick={prepare}>
        {generating ? '正在生成复盘…' : '准备今天的复盘'}
      </Button>
      {todayContent && !generating && (
        <Button size="sm" variant="outline" icon={<RefreshCw size={14} />} disabled={disabled || todayContent.state === 'UNKNOWN'} onClick={() => setRefreshOpen(true)}>
          更新今天的复盘
        </Button>
      )}
    </>}
  >
    {blocked && (
      <div className={styles.notice} role="note">
        <Info size={16} aria-hidden="true" />
        <div>
          <strong>{!s.enabled ? '生活复盘尚未启用' : '生活复盘尚未完成用途授权'}</strong>
          <p>授权读取范围与模型后即可使用。</p>
        </div>
        {onOpenSettings && <Button size="sm" variant="outline" onClick={() => onOpenSettings('review')}>前往复盘设置</Button>}
      </div>
    )}

    {!shown && (
      <ReviewEmptyState
        title={latest ? '今天还没有复盘' : '还没有生活复盘'}
        description={`每天 ${formatHour(hour)} 自动生成，也可以现在手动准备。`}
      />
    )}

    {shown && !todayContent && <p className={styles.hint}>今天尚未生成，以下为 {dayTitle(shown.day)} 的回顾</p>}
    {shown && (
      <div className={styles.reading}>
        <LifeContentCard key={shown.id} className={styles.embedded} content={shown} timezone={shown.timezone || s.timezone}
          busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />
      </div>
    )}

    {latestIsFailedPast && latest && (
      <div className={styles.prevFailure}>
        <button type="button" className={styles.prevToggle} aria-expanded={expandFailedPrev} onClick={() => setExpandFailedPrev(v => !v)}>
          <span>最近一次（{dayTitle(latest.day)}）未生成{latest.message ? `：${latest.message}` : ''}</span>
          <ChevronDown size={14} className={expandFailedPrev ? styles.chevronOpen : ''} aria-hidden="true" />
        </button>
        {expandFailedPrev && (
          <div className={styles.reading}>
            <LifeContentCard key={latest.id} className={styles.embedded} content={latest} timezone={latest.timezone || s.timezone}
              busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />
          </div>
        )}
      </div>
    )}

    <ConfirmDialog
      open={refreshOpen}
      title="更新今天的复盘"
      tone="info"
      confirmText="确认更新"
      busy={busy}
      description="重新采集截至现在的资料并生成新版本，旧版与已发副本保留。"
      onClose={() => setRefreshOpen(false)}
      onConfirm={() => {
        setRefreshOpen(false);
        if (todayContent) void act(() => lifeReviewApi.prepare(today, todayContent.version, true), '已安排更新今天的复盘');
      }}
    />
  </StatusCard>;
}
