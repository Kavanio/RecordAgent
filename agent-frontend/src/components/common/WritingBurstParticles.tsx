import type { CSSProperties } from 'react';
import { Circle, Heart, Sparkle, Star } from 'lucide-react';
import type { WritingEffectPreferences } from '../../types/writingEffects';
import styles from './WritingBurstParticles.module.css';

interface WritingBurstParticlesProps {
  preferences: WritingEffectPreferences;
  seed: number;
}

const ICONS = [Sparkle, Heart, Star, Circle];

/** 字尾向右喷出后逐渐分成上、中、下三束；同束粒子有前后距离和速度差。 */
function particleMotion(seed: number, index: number): CSSProperties {
  let state = Math.imul(seed + 1, 0x9e3779b1) ^ Math.imul(index + 1, 0x85ebca6b);
  const random = () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 0x100000000;
  };
  const branch = index % 3 - 1;
  const endX = index < 3 ? 24 + random() * 8 : 38 + random() * 12;
  const endY = branch * endX * (.38 + random() * .18) + (random() - .5) * 4;
  const spin = (random() < .5 ? -1 : 1) * (45 + random() * 90);
  const px = (value: number) => `${value.toFixed(2)}px`;
  return {
    // 起段集中向前，随后逐渐张开；不会在出生点四周原地堆成一团。
    '--kick-x': px(endX * .32), '--kick-y': px(endY * .12),
    '--float-x': px(endX * .72), '--float-y': px(endY * .58),
    '--end-x': px(endX), '--end-y': px(endY),
    '--start-turn': `${((random() - .5) * 40).toFixed(1)}deg`,
    '--float-turn': `${(spin * .55).toFixed(1)}deg`, '--end-turn': `${spin.toFixed(1)}deg`,
    '--duration': `${Math.round(320 + random() * 100)}ms`, '--delay': `${Math.round(random() * 18)}ms`,
  } as CSSProperties;
}

/** 编辑器与设置预览共用的彩色粒子；所有图片与 emoji 统一使用 14px 画布。 */
export function WritingBurstParticles({ preferences, seed }: WritingBurstParticlesProps) {
  const { style, emojis, images } = preferences;
  const kinds = style === 'mixed' ? ['icons', 'emoji', ...(images.length ? ['images'] : [])] : [style];
  return <span className={styles.origin} aria-hidden="true">
    {Array.from({ length: 6 }, (_, index) => {
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
