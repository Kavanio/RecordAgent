import { useEffect, useState } from 'react';
import type { LifeContent } from '../../../types/lifeReview';
import { dayTitle, type ReviewDay } from '../../../features/review/statusPresentation';
import { Drawer } from '../../common/Drawer';
import { LifeContentCard } from '../LifeContentCard';
import { LifeReviewDetailsContent } from '../LifeReviewDetails';
import styles from './ReviewDayDrawer.module.css';

type DrawerView = 'content' | 'references' | 'version';

interface ReviewDayDrawerProps {
  reviewDay: ReviewDay | null;
  timezone: string;
  busy: boolean;
  authorized: boolean;
  feishu: boolean;
  onClose: () => void;
  act: (work: () => Promise<unknown>, message: string) => Promise<void>;
}

/** 按日复盘阅读抽屉：单层抽屉内切换版本与正文/参考记录/版本信息，避免多层抽屉叠加。 */
export function ReviewDayDrawer({ reviewDay, timezone, busy, authorized, feishu, onClose, act }: ReviewDayDrawerProps) {
  const [selectedId, setSelectedId] = useState<string>('');
  const [mode, setMode] = useState<DrawerView>('content');

  useEffect(() => {
    if (reviewDay) {
      setSelectedId(reviewDay.latest.id);
      setMode('content');
    }
  }, [reviewDay]);

  if (!reviewDay) return null;
  const current: LifeContent = reviewDay.versions.find(v => v.id === selectedId) ?? reviewDay.latest;

  return <Drawer
    open
    width={640}
    title={`${dayTitle(reviewDay.day)} · 复盘详情`}
    description={`${reviewDay.day} · 共 ${reviewDay.versions.length} 个不可变版本`}
    onClose={onClose}
  >
    <div className={styles.wrap}>
      {reviewDay.versions.length > 1 && (
        <div className={styles.versions} role="group" aria-label="切换版本">
          {reviewDay.versions.map((v, idx) => <button key={v.id} type="button"
            aria-pressed={current.id === v.id}
            onClick={() => setSelectedId(v.id)}>
            v{v.version}{idx === 0 ? '（最新）' : ''}
          </button>)}
        </div>
      )}

      <div className={styles.views} role="tablist" aria-label="复盘内容视角">
        {([
          ['content', '复盘正文'],
          ['references', '参考记录'],
          ['version', '版本信息'],
        ] as const).map(([key, label]) => <button key={key} type="button" role="tab"
          aria-selected={mode === key}
          onClick={() => setMode(key)}>{label}</button>)}
      </div>

      {mode === 'content' ? (
        <LifeContentCard key={current.id} className={styles.card} hideDetailButtons content={current}
          timezone={current.timezone || timezone} busy={busy} authorized={authorized} feishu={feishu} act={act} />
      ) : (
        <LifeReviewDetailsContent key={`${current.id}-${mode}`} content={current} timezone={current.timezone || timezone} mode={mode} />
      )}
    </div>
  </Drawer>;
}
