import { useState } from 'react';
import { Button } from '../common/Button';
import { ReviewSettings } from './ReviewSettings';
import { LifeReviewSettings } from './LifeReviewSettings';
import styles from './ReviewWorkspace.module.css';

/** 复盘入口区分生活正文授权与既有统计摘要授权。 */
export function ReviewWorkspace() {
  const [tab, setTab] = useState<'life' | 'statistics'>('life');
  return <div className={styles.workspace}>
    <div className={styles.tabs} aria-label="复盘类型">
      <Button size="sm" variant={tab === 'life' ? 'primary' : 'ghost'} aria-pressed={tab === 'life'} onClick={() => setTab('life')}>生活复盘与主动联系</Button>
      <Button size="sm" variant={tab === 'statistics' ? 'primary' : 'ghost'} aria-pressed={tab === 'statistics'} onClick={() => setTab('statistics')}>统计复盘</Button>
    </div>
    {tab === 'life' ? <LifeReviewSettings /> : <ReviewSettings />}
  </div>;
}
