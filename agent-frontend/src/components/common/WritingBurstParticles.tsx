import type { CSSProperties } from 'react';
import { Circle, Heart, Sparkle, Star } from 'lucide-react';
import type { WritingEffectPreferences } from '../../types/writingEffects';
import styles from './WritingBurstParticles.module.css';

interface WritingBurstParticlesProps {
  preferences: WritingEffectPreferences;
  seed: number;
}

const ICONS = [Sparkle, Heart, Star, Circle];

/** 按次生成稳定的小簇抛射轨迹，方向不均分、远近不同，后半段受轻微重力影响。 */
function particleMotion(seed: number, index: number): CSSProperties {
  let state = Math.imul(seed + 1, 0x9e3779b1) ^ Math.imul(index + 1, 0x85ebca6b);
  const random = () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 0x100000000;
  };
  // 大部分向上或侧上方弹出，少量侧下方点缀，避免八等分圆环或完全无序。
  const angle = index % 4 === 3 ? random() * Math.PI : -Math.PI + random() * Math.PI;
  const distance = 12 + random() * 16;
  const vx = Math.cos(angle) * distance;
  const vy = Math.sin(angle) * distance;
  const drift = (random() - .5) * 8;
  const gravity = 10 + random() * 8;
  let endX = vx + drift, endY = vy + gravity;
  const limit = Math.min(1, 34 / Math.hypot(endX, endY));
  endX *= limit; endY *= limit;
  const spin = (random() < .5 ? -1 : 1) * (90 + random() * 140);
  const px = (value: number) => `${value.toFixed(2)}px`;
  return {
    '--kick-x': px(vx * .62), '--kick-y': px(vy * .62),
    '--float-x': px(vx * .92 + drift * .3), '--float-y': px(vy * .92 + gravity * .15),
    '--end-x': px(endX), '--end-y': px(endY),
    '--start-turn': `${((random() - .5) * 40).toFixed(1)}deg`,
    '--float-turn': `${(spin * .55).toFixed(1)}deg`, '--end-turn': `${spin.toFixed(1)}deg`,
    '--duration': `${Math.round(420 + random() * 90)}ms`, '--delay': `${Math.round(random() * 24)}ms`,
  } as CSSProperties;
}

/** 编辑器与设置预览共用的彩色粒子；所有图片与 emoji 统一使用 14px 画布。 */
export function WritingBurstParticles({ preferences, seed }: WritingBurstParticlesProps) {
  const { style, emojis, images } = preferences;
  const kinds = style === 'mixed' ? ['icons', 'emoji', ...(images.length ? ['images'] : [])] : [style];
  return <span className={styles.origin} aria-hidden="true">
    {Array.from({ length: 8 }, (_, index) => {
      const pick = seed * 7 + index * 5;
      const kind = kinds[(seed + index) % kinds.length];
      const image = images[pick % images.length];
      const Icon = ICONS[pick % ICONS.length];
      return <span key={index} className={styles.particle} style={particleMotion(seed, index)}>
        {kind === 'images' && image ? <img src={image.dataUrl} alt="" draggable={false} />
          : kind === 'emoji' ? <span className={styles.emoji}>{emojis[pick % emojis.length] ?? '✨'}</span>
            : <Icon className={styles.icon} strokeWidth={2.4} />}
      </span>;
    })}
  </span>;
}
