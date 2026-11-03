import type { WritingEffectImage, WritingEffectPreferences } from '../types/writingEffects';
import { readPreference, writePreferenceConfirmed } from './desktopPreferences';

const STORAGE_KEY = 'butvan.writingEffects';
const CHANGE_EVENT = 'butvan:writing-effects-changed';
export const MAX_EFFECT_IMAGES = 8;
export const MAX_EFFECT_IMAGE_URL_LENGTH = 18000;
export const WRITING_EFFECT_EMOJIS = ['✨', '🌟', '💖', '🍀', '🌸', '🦋', '🍊', '🍒', '🌈', '🫧', '🎈', '💫'] as const;
let pendingWrite = Promise.resolve();

function isImage(value: unknown): value is WritingEffectImage {
  if (!value || typeof value !== 'object') return false;
  const image = value as Partial<WritingEffectImage>;
  return typeof image.id === 'string' && image.id.length <= 80
    && typeof image.name === 'string' && image.name.length <= 80
    && typeof image.dataUrl === 'string' && image.dataUrl.length <= MAX_EFFECT_IMAGE_URL_LENGTH
    && /^data:image\/png;base64,[A-Za-z0-9+/]+={0,2}$/.test(image.dataUrl);
}

function normalize(value: Partial<WritingEffectPreferences> | null): WritingEffectPreferences {
  const images = Array.isArray(value?.images)
    ? value.images.filter(isImage).filter((image, index, items) => items.findIndex(item => item.id === image.id) === index).slice(0, MAX_EFFECT_IMAGES) : [];
  const emojis = Array.isArray(value?.emojis)
    ? WRITING_EFFECT_EMOJIS.filter(emoji => value.emojis?.includes(emoji)) : [...WRITING_EFFECT_EMOJIS];
  const style = ['mixed', 'icons', 'emoji', 'images'].includes(value?.style ?? '') ? value!.style! : 'mixed';
  return {
    enabled: typeof value?.enabled === 'boolean' ? value.enabled : true,
    style: style === 'images' && !images.length ? 'mixed' : style,
    emojis: emojis.length ? emojis : [...WRITING_EFFECT_EMOJIS],
    images,
  };
}

/** 配置与小图同存于桌面长期偏好；缺失、损坏或旧配置采用鲜明混合默认样式。 */
export function getWritingEffectPreferences(): WritingEffectPreferences {
  try { return normalize(JSON.parse(readPreference(STORAGE_KEY) ?? '{}')); }
  catch { return normalize(null); }
}

/** 串行合并并持久化，再通知编辑器；失败不发布成功状态或覆盖已保存设置。 */
export function updateWritingEffectPreferences(patch: Partial<WritingEffectPreferences>): Promise<WritingEffectPreferences> {
  const result = pendingWrite.then(async () => {
    const next = normalize({ ...getWritingEffectPreferences(), ...patch });
    const serialized = JSON.stringify(next);
    if (new TextEncoder().encode(serialized).length > 200 * 1024) throw new Error('特效图片总量过大，请先删除部分图片');
    await writePreferenceConfirmed(STORAGE_KEY, serialized);
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
    return next;
  });
  pendingWrite = result.then(() => undefined, () => undefined);
  return result;
}

export function subscribeWritingEffectPreferences(listener: () => void): () => void {
  window.addEventListener(CHANGE_EVENT, listener);
  return () => window.removeEventListener(CHANGE_EVENT, listener);
}
