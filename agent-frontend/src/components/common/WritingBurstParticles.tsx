import { Circle, Heart, Sparkle, Star } from 'lucide-react';
import type { WritingEffectPreferences } from '../../types/writingEffects';
import styles from './WritingBurstParticles.module.css';

interface WritingBurstParticlesProps {
  preferences: WritingEffectPreferences;
  seed: number;
}

const ICONS = [Sparkle, Heart, Star, Circle];

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
      return <span key={index} className={styles.particle}>
        {kind === 'images' && image ? <img src={image.dataUrl} alt="" draggable={false} />
          : kind === 'emoji' ? <span className={styles.emoji}>{emojis[pick % emojis.length] ?? '✨'}</span>
            : <Icon className={styles.icon} strokeWidth={2.4} />}
      </span>;
    })}
  </span>;
}
