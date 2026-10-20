import { useEffect, useRef, useState } from 'react';
import styles from './AnimatedNumber.module.css';

interface AnimatedNumberProps {
  value: number;
  /** 数据未就绪时显示占位符，不提前消耗入场动画。 */
  ready?: boolean;
  format?: (value: number) => string;
  /** 动画中保留的小数位；金额使用 2，计数默认 0。 */
  fractionDigits?: 0 | 1 | 2;
}

const plainNumber = (value: number) => String(value);
const DURATION_MS = 600;

/** 首次数据就绪后轻量递增；后续实时更新直达真实值，读屏始终读取最终统计。 */
export function AnimatedNumber({ value, ready = true, format = plainNumber, fractionDigits = 0 }: AnimatedNumberProps) {
  const [progress, setProgress] = useState(0);
  const completed = useRef(false);

  useEffect(() => {
    if (!ready || completed.current) return;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    let frame = 0;
    let startedAt: number | null = null;
    const finish = () => {
      completed.current = true;
      window.cancelAnimationFrame(frame);
      setProgress(1);
    };
    if (!media || media.matches) { finish(); return; }
    const tick = (time: number) => {
      startedAt ??= time;
      const elapsed = Math.min(1, Math.max(0, (time - startedAt) / DURATION_MS));
      setProgress(1 - (1 - elapsed) ** 3);
      if (elapsed < 1) frame = window.requestAnimationFrame(tick);
      else completed.current = true;
    };
    const onPreferenceChange = () => { if (media.matches) finish(); };
    media.addEventListener('change', onPreferenceChange);
    frame = window.requestAnimationFrame(tick);
    return () => {
      window.cancelAnimationFrame(frame);
      media.removeEventListener('change', onPreferenceChange);
    };
  }, [ready]);

  const target = Number.isFinite(value) ? value : 0;
  const scale = 10 ** fractionDigits;
  const displayed = Math.round(target * progress * scale) / scale;
  return <span className={styles.number}>
    <span aria-hidden="true">{ready ? format(displayed) : '—'}</span>
    <span className={styles.accessible}>{ready ? format(target) : '正在加载'}</span>
  </span>;
}
