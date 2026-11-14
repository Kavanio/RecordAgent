import { HeartHandshake } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeCompanionView } from '../../../types/lifeReview';
import { companionPulse, formatDateTime } from '../../../features/review/statusPresentation';
import { Button } from '../../common/Button';
import { StatusCard } from './StatusCard';
import styles from './CompanionPulseCard.module.css';

interface CompanionPulseCardProps {
  companion: LifeCompanionView | null;
  timezone: string;
  busy: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 陪伴节奏卡：一句话说明主动问候处于什么状态，以及最近一次学习与互动时间。 */
export function CompanionPulseCard({ companion, timezone, busy, act }: CompanionPulseCardProps) {
  if (!companion) {
    return <StatusCard title="主动陪伴" icon={<HeartHandshake size={16} />} tone="muted"
      description="当前后端未提供陪伴状态，升级后可在这里查看主动问候节奏。" />;
  }
  const { rhythm, cognition } = companion;
  const pulse = companionPulse(companion.companionEnabled, rhythm, cognition, timezone);

  return <StatusCard
    title="主动陪伴"
    icon={<HeartHandshake size={16} />}
    actions={<Button size="sm" variant={companion.companionEnabled ? 'ghost' : 'primary'} disabled={busy}
      onClick={() => void act(() => lifeReviewApi.enableCompanion({ enabled: !companion.companionEnabled, feishu: companion.feishuConnected, timezone }),
        companion.companionEnabled ? '已暂停不定时主动问候' : '已开启系统性认知学习与不定时主动问候')}>
      {companion.companionEnabled ? '暂停' : '开启主动陪伴'}
    </Button>}
  >
    <div className={styles.pulse} data-tone={pulse.tone}>
      <span className={styles.dot} aria-hidden="true" />
      <p>{pulse.text}</p>
    </div>
    <p className={styles.metaLine}>
      <span>{companion.primarySessionTitle || '日常陪伴'}</span>
      <span aria-hidden="true">·</span>
      <span>{companion.feishuLabel || (companion.feishuConnected ? '本人飞书' : '仅桌面')}</span>
      <span aria-hidden="true">·</span>
      <span>学习 {formatDateTime(rhythm.lastLearnedAt, timezone)}</span>
      <span aria-hidden="true">·</span>
      <span>问候 {formatDateTime(rhythm.lastOutreachAt, timezone)}</span>
    </p>
    <div className={styles.actions}>
      <Button size="sm" variant="outline" disabled={busy} onClick={() => void act(() => lifeReviewApi.reachOutCompanion(), '')}>问候一次</Button>
      <Button size="sm" variant="outline" disabled={busy} onClick={() => void act(() => lifeReviewApi.learnCompanion(), '已从近期全量记录更新对你的系统性认知')}>更新认知</Button>
    </div>
  </StatusCard>;
}
