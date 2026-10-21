import { ChartSkeleton } from './ChartSkeleton';
import styles from './Skeleton.module.css';

export interface SkeletonProps {
  variant?: 'text' | 'metrics' | 'list' | 'chart' | 'document' | 'timeline' | 'heatmap';
  label?: string;
  /** 日期格等已有朗读语义的容器使用装饰模式，避免重复播报。 */
  decorative?: boolean;
}

/** 布局感知的有界占位；所有图形均为装饰，不输出虚假业务值或操作控件。 */
export function Skeleton({ variant = 'list', label = '正在读取数据…', decorative = false }: SkeletonProps) {
  if (variant === 'chart') return <ChartSkeleton label={label} decorative={decorative} />;
  const count = variant === 'metrics' || variant === 'document' ? 4 : variant === 'text' ? 2 : 3;
  return <span className={`${styles.root} ${styles[variant]}`} role={decorative ? undefined : 'status'}
    aria-label={decorative ? undefined : label} aria-hidden={decorative || undefined}>
    {variant === 'heatmap' ? <svg viewBox="0 0 688 90" preserveAspectRatio="xMidYMid meet" aria-hidden="true" focusable="false">
      {Array.from({ length: 53 }, (_, week) => Array.from({ length: 7 }, (_, day) => <rect key={`${week}-${day}`} x={week * 13} y={day * 13} width="9" height="9" rx="2" />))}
    </svg> : Array.from({ length: count }, (_, index) => variant === 'text' ? <span className={styles.block} key={index} aria-hidden="true" />
      : variant === 'timeline' ? <span className={styles.item} key={index} aria-hidden="true">
        <span className={styles.copy}><span className={`${styles.block} ${styles.title}`} /><span className={`${styles.block} ${styles.meta}`} /></span>
        <span className={styles.timeTrack}><span className={`${styles.block} ${styles.timeSegment}`} /></span>
      </span> : <span className={styles.item} key={index} aria-hidden="true">
        {variant !== 'metrics' && <span className={`${styles.block} ${styles.marker}`} />}
        <span className={styles.copy}><span className={`${styles.block} ${styles.title}`} /><span className={`${styles.block} ${styles.line}`} />
          {variant === 'document' && <span className={`${styles.block} ${styles.excerpt}`} />}
          {(variant === 'document' || variant === 'metrics') && <span className={`${styles.block} ${styles.meta}`} />}
        </span>
        {(variant === 'list' || variant === 'document') && <span className={`${styles.block} ${styles.trailing}`} />}
      </span>)}
  </span>;
}
