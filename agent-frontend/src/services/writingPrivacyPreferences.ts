import type { WritingPrivacyPreferences } from '../types/writingPrivacy';
import { readPreference, writePreferenceConfirmed } from './desktopPreferences';

const KEY = 'butvan.writingPrivacy';
const EVENT = 'butvan:writing-privacy-changed';
let pending = Promise.resolve();

/** 避开保存、撤销、窗口与浏览器常用快捷键；固定组合避免误吞普通输入。 */
export function isPrivacyShortcutKey(key: string): boolean {
  return /^[a-z]$/.test(key) && !'npqrstwz'.includes(key);
}

export function privacyShortcutLabel(key: string): string {
  return key ? `⌘/Ctrl + Shift + ${key.toUpperCase()}` : '未设置';
}

export function getWritingPrivacyPreferences(): WritingPrivacyPreferences {
  let value: Partial<WritingPrivacyPreferences> | null = null;
  try { value = JSON.parse(readPreference(KEY) ?? '{}'); } catch { /* 损坏配置采用默认值。 */ }
  return {
    enabled: value?.enabled === true,
    shortcut: value?.shortcut === '' || (typeof value?.shortcut === 'string' && isPrivacyShortcutKey(value.shortcut))
      ? value.shortcut : 'h',
  };
}

/** 串行合并配置，持久化成功后同步设置页与编辑器。 */
export function updateWritingPrivacyPreferences(patch: Partial<WritingPrivacyPreferences>): Promise<void> {
  const result = pending.then(async () => {
    if (patch.shortcut !== undefined && patch.shortcut !== '' && !isPrivacyShortcutKey(patch.shortcut)) {
      throw new Error('请选择其他字母，避免与常用快捷键冲突');
    }
    await writePreferenceConfirmed(KEY, JSON.stringify({ ...getWritingPrivacyPreferences(), ...patch }));
    window.dispatchEvent(new CustomEvent(EVENT));
  });
  pending = result.catch(() => {});
  return result;
}

export function subscribeWritingPrivacyPreferences(listener: () => void): () => void {
  window.addEventListener(EVENT, listener);
  return () => window.removeEventListener(EVENT, listener);
}
