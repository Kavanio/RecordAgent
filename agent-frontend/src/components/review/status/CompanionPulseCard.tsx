import { HeartHandshake, Pause } from 'lucide-react';
import { lifeReviewApi } from '../../../services/lifeReviewApi';
import type { LifeCompanionView } from '../../../types/lifeReview';
import { companionPulse, formatCompanionChannel, formatDateTime } from '../../../features/review/statusPresentation';
import { Button } from '../../common/Button';
import { StatusCard } from './StatusCard';
import styles from './CompanionPulseCard.module.css';

interface CompanionPulseCardProps {
  companion: LifeCompanionView | null;
  timezone: string;
  busy: boolean;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 陪伴节奏卡：直观展示启停状态、当前节奏语与轻量化学习/问候入口。 */
export function CompanionPulseCard({ companion, timezone, busy, act }: CompanionPulseCardProps) {
  if (!companion) {
    return <StatusCard title="主动陪伴" icon={<HeartHandshake size={15} />} tone="muted" variant="open"
      description="当前后端未提供陪伴状态，升级后可查看主动问候节奏。" />;
  }
  const { rhythm, cognition } = companion;
  const pulse = companionPulse(companion.companionEnabled, rhythm, cognition, timezone);
  const channel = formatCompanionChannel(companion.feishuLabel, companion.feishuConnected);
  const channelTitle = `${companion.primarySessionTitle || '日常陪伴'} · ${companion.feishuLabel || channel}`;

  return <StatusCard
    title="主动陪伴"
    icon={<HeartHandshake size={15} />}
    variant="open"
    className={styles.section}
    badge={
      <span className={styles.stateBadge} data-on={companion.companionEnabled || undefined}>
        <i aria-hidden="true" />
        {companion.companionEnabled ? '开启' : '已暂停'}
      </span>
    }
    actions={
      companion.companionEnabled ? (
        <Button size="sm" variant="outline" icon={<Pause size={12} />} disabled={busy}
          onClick={() => void act(() => lifeReviewApi.enableCompanion({ enabled: false, feishu: companion.feishuConnected, timezone }),
            '已暂停不定时主动问候')}>
          暂停
        </Button>
      ) : (
        <Button size="sm" variant="primary" disabled={busy}
          onClick={() => void act(() => lifeReviewApi.enableCompanion({ enabled: true, feishu: companion.feishuConnected, timezone }),
            '已开启系统性认知学习与不定时主动问候')}>
          开启主动陪伴
        </Button>
      )
    }
  >
    <div className={styles.pulse} data-tone={pulse.tone}>
      <span className={styles.dot} aria-hidden="true" />
      <p>{pulse.text}</p>
    </div>
    <p className={styles.metaLine}>
      <span className={styles.channelTag} title={channelTitle}>{channel}</span>
      <span className={styles.metaTimes}>
        <span>学习 {formatDateTime(rhythm.lastLearnedAt, timezone)}</span>
        <span aria-hidden="true">·</span>
        <span>问候 {formatDateTime(rhythm.lastOutreachAt, timezone)}</span>
      </span>
    </p>
    <div className={styles.actions}>
      <Button size="sm" variant="ghost" disabled={busy} onClick={() => void act(() => lifeReviewApi.reachOutCompanion(), '')}>问候一次</Button>
      <Button size="sm" variant="ghost" disabled={busy} onClick={() => void act(() => lifeReviewApi.learnCompanion(), '已从近期全量记录更新对你的系统性认知')}>更新认知</Button>
    </div>
  </StatusCard>;
}
