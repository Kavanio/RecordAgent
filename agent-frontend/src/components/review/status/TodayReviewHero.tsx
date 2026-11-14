import { useState } from 'react';
import { CalendarCheck, Info, RefreshCw, Sparkles } from 'lucide-react';
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

/** 今日复盘主卡：未启用引导、准备/更新、生成中与正文直接阅读；历史版本移到「足迹」。 */
export function TodayReviewHero({ settings: s, history, habit, today, busy, generating, disabled, act, onOpenSettings }: TodayReviewHeroProps) {
  const [refreshOpen, setRefreshOpen] = useState(false);
  const ordered = sortVersions(history);
  const todayContent = ordered.find(c => c.day === today);
  const shown = todayContent ?? ordered[0];
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
    description={`${dayTitle(today)} · 每天 ${formatHour(hour)} 自动准备${s.feishu ? '，完成后投递到本人飞书' : ''}`}
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
          <p>启用并确认读取范围与模型授权后，即可按设定时间或手动准备今日复盘。</p>
        </div>
        {onOpenSettings && <Button size="sm" variant="outline" onClick={() => onOpenSettings('review')}>前往复盘设置</Button>}
      </div>
    )}

    {!shown && <ReviewEmptyState title="还没有生活复盘" description="准备一份当天复盘后，正文会直接显示在这里，历史版本可以在「足迹」中按日期查看。" />}
    {shown && !todayContent && <p className={styles.hint}>今天尚无复盘，以下为最近一天（{dayTitle(shown.day)}）的记录。</p>}
    {shown && (
      <div className={styles.reading}>
        <LifeContentCard key={shown.id} className={styles.embedded} content={shown} timezone={shown.timezone || s.timezone}
          busy={busy} authorized={!disabled} feishu={s.feishu} act={act} />
      </div>
    )}

    <ConfirmDialog
      open={refreshOpen}
      title="更新今天的复盘"
      tone="info"
      confirmText="确认更新"
      busy={busy}
      description="重新采集已授权范围内截至现在的资料，并生成新版本，可能占用一次模型调用额度。旧版本与已发送副本保留。"
      onClose={() => setRefreshOpen(false)}
      onConfirm={() => {
        setRefreshOpen(false);
        if (todayContent) void act(() => lifeReviewApi.prepare(today, todayContent.version, true), '已安排更新今天的复盘');
      }}
    />
  </StatusCard>;
}
