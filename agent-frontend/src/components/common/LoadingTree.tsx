import React, { useId } from 'react';
import styles from './LoadingTree.module.css';

export interface LoadingTreeProps {
  label?: string;
  size?: 'small' | 'medium' | 'large';
  className?: string;
}

// 单条闭合轮廓从左下树根起笔，经左侧树冠、树顶和右侧回到根部，不以多个子路径同时闪现。
const TREE_OUTLINE = 'M28 102 C36 96 40 86 41 72 C31 77 19 73 18 63 C8 62 6 50 13 43 C7 33 14 21 25 22 C25 10 39 5 48 13 C57 3 71 10 72 20 C84 19 91 31 85 41 C95 50 89 63 79 64 C78 75 65 79 55 72 C56 86 60 96 68 102 Q48 97 28 102 Z';

/** 统一大树等待动画：外围一笔成型、笔尖追光、枝叶渐生；仅使用 SVG 与 CSS。 */
export const LoadingTree: React.FC<LoadingTreeProps> = ({
  label = '正在加载…',
  size = 'medium',
  className = '',
}) => {
  const id = useId();
  const inkId = `${id}-tree-ink`;
  const washId = `${id}-tree-wash`;
  return (
    <div className={`${styles.root} ${styles[size]} ${className}`} role="status" aria-live="polite" aria-busy="true">
      <svg className={styles.tree} viewBox="0 0 96 112" fill="none" aria-hidden="true" focusable="false">
        <defs>
          <linearGradient id={inkId} x1="24" y1="10" x2="66" y2="102" gradientUnits="userSpaceOnUse">
            <stop stopColor="#86a997" />
            <stop offset=".48" stopColor="#507f78" />
            <stop offset="1" stopColor="#637d94" />
          </linearGradient>
          <radialGradient id={washId} cx="48" cy="36" r="55" gradientUnits="userSpaceOnUse">
            <stop stopColor="#b4d4bc" stopOpacity=".44" />
            <stop offset="1" stopColor="#a3bfce" stopOpacity="0" />
          </radialGradient>
        </defs>
        <ellipse className={styles.aura} cx="48" cy="54" rx="39" ry="43" fill={`url(#${washId})`} />
        <path className={styles.guide} d={TREE_OUTLINE} stroke={`url(#${inkId})`} />
        <g className={styles.growth} stroke={`url(#${inkId})`}>
          <path className={styles.wash} data-tree-part="wash" d={TREE_OUTLINE} fill={`url(#${washId})`} stroke="none" />
          <path className={`${styles.draw} ${styles.outline}`} data-tree-part="outline" pathLength="1" d={TREE_OUTLINE} />
          <path className={styles.trace} data-tree-part="trace" pathLength="1" d={TREE_OUTLINE} />
          <path className={`${styles.draw} ${styles.stem}`} data-tree-part="stem" pathLength="1" d="M48 99 C46 83 47 65 48 43" />
          <g className={styles.branches} data-tree-part="branches">
            <path className={`${styles.draw} ${styles.branchEarly}`} pathLength="1" d="M47 74 C39 67 32 62 27 52" />
            <path className={`${styles.draw} ${styles.branchEarly}`} pathLength="1" d="M48 68 C59 63 66 55 69 43" />
            <path className={`${styles.draw} ${styles.branchLate}`} pathLength="1" d="M48 58 C42 52 37 44 36 33" />
            <path className={`${styles.draw} ${styles.branchLate}`} pathLength="1" d="M48 49 C53 43 57 35 57 26" />
          </g>
          <g className={styles.leaves} data-tree-part="leaves" fill="#83a798" stroke="none">
            <path className={`${styles.leaf} ${styles.leafEarly}`} d="M27 52 C19 52 19 44 20 42 C26 43 30 47 27 52 Z" />
            <path className={`${styles.leaf} ${styles.leafEarly}`} d="M69 43 C67 35 74 32 77 32 C78 38 75 43 69 43 Z" />
            <path className={`${styles.leaf} ${styles.leafLate}`} d="M36 33 C29 32 29 26 30 23 C36 24 39 28 36 33 Z" />
            <path className={`${styles.leaf} ${styles.leafLate}`} d="M57 26 C54 20 59 15 62 14 C65 20 63 25 57 26 Z" />
          </g>
          <path className={`${styles.draw} ${styles.ground}`} data-tree-part="ground" pathLength="1" d="M23 106 Q48 102 73 106" />
        </g>
      </svg>
      {label && <span className={styles.label}>{label}</span>}
    </div>
  );
};
