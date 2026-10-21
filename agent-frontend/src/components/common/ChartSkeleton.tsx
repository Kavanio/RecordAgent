import styles from './ChartSkeleton.module.css';

interface ChartSkeletonProps {
  variant?: 'trend' | 'bars';
  label?: string;
  decorative?: boolean;
}

const HEIGHTS = [52, 78, 64, 104, 86, 120, 98, 76, 112, 90, 132, 108];

/** 图表专用占位：细网格、窄柱与中性曲线；不提供虚假金额、日期或可交互数据点。 */
export function ChartSkeleton({ variant = 'trend', label = '正在读取图表…', decorative = false }: ChartSkeletonProps) {
  const heights = variant === 'bars' ? HEIGHTS.filter((_, index) => index % 2 === 0) : HEIGHTS;
  return <span className={styles.root} role={decorative ? undefined : 'status'} aria-label={decorative ? undefined : label} aria-hidden={decorative || undefined}>
    <svg className={styles.graphic} viewBox="0 0 720 210" preserveAspectRatio="none" aria-hidden="true" focusable="false">
      {[24, 62, 100, 138, 176].map(y => <g key={y}>
        <rect className={styles.tick} x="2" y={y - 3} width="20" height="5" rx="2.5" />
        <line className={styles.grid} x1="40" x2="706" y1={y} y2={y} />
      </g>)}
      <g className={styles.bars}>{heights.map((height, index) => <rect key={index} x={56 + index * 634 / heights.length}
        y={176 - height} width={variant === 'bars' ? 26 : 16} height={height} rx="4" />)}</g>
      {variant === 'trend' && <>
        <path className={styles.area} d="M48 142 C104 148 116 98 170 108 S248 142 300 90 S376 106 428 70 S508 104 560 56 S644 78 700 36 L700 176 H48 Z" />
        <path className={styles.curve} d="M48 142 C104 148 116 98 170 108 S248 142 300 90 S376 106 428 70 S508 104 560 56 S644 78 700 36" />
      </>}
      {[56, 178, 300, 422, 544, 666].map(x => <rect className={styles.tick} key={x} x={x} y="194" width="29" height="5" rx="2.5" />)}
    </svg>
    <span className={styles.caption} aria-hidden="true"><i />{label}</span>
  </span>;
}
