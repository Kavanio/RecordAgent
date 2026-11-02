export type WritingEffectStyle = 'mixed' | 'icons' | 'emoji' | 'images';

/** 已缩小为统一透明 PNG 的本机特效图片，不保存用户原图。 */
export interface WritingEffectImage {
  id: string;
  name: string;
  dataUrl: string;
}

export interface WritingEffectPreferences {
  enabled: boolean;
  style: WritingEffectStyle;
  emojis: string[];
  images: WritingEffectImage[];
}
