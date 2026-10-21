import styles from './Skeleton.module.css';

export interface SkeletonProps {
  variant?: 'text' | 'metrics' | 'list' | 'chart';
  label?: string;
  /** 日期格等已有朗读语义的容器使用装饰模式，避免重复播报。 */
  decorative?: boolean;
}

/** 有界布局占位；不输出虚假金额或空数据，减少动态效果时保持静态。 */
export function Skeleton({ variant = 'list', label = '正在读取数据…', decorative = false }: SkeletonProps) {
  const count = variant === 'metrics' ? 4 : variant === 'text' ? 2 : 3;
  return <span className={`${styles.root} ${styles[variant]}`} role={decorative ? undefined : 'status'}
    aria-label={decorative ? undefined : label} aria-hidden={decorative || undefined}>
    {Array.from({ length: count }, (_, index) => <span className={styles.block} key={index} aria-hidden="true" />)}
  </span>;
}
