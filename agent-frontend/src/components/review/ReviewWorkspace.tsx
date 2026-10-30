import { ReviewSettings } from './ReviewSettings';
import { LifeReviewSettings } from './LifeReviewSettings';
import styles from './ReviewWorkspace.module.css';

interface Props { kind?: 'life' | 'statistics' }
/** 复盘类型由设置侧栏选择，内容区从页面标题开始，不再叠加导航栏。 */
export function ReviewWorkspace({ kind = 'life' }: Props) {
  return <div className={styles.workspace}>
    {kind === 'life' ? <LifeReviewSettings /> : <ReviewSettings />}
  </div>;
}
