import { invoke, isTauri } from '@tauri-apps/api/core';

/**
 * 桌面端长期偏好统一入口。
 *
 * 权威来源是 `~/.butvan-agent/desktop/preferences.json`（与后端业务数据同寿命），
 * localStorage 仅作为同步读缓存与浏览器开发环境兜底。删除或重装应用、第三方
 * 卸载工具清理 `~/Library` 都不会再丢掉用户习惯。
 */
const cache = new Map<string, string>();
let initialized = false;

/** 旧版本只存在于 WebView localStorage 的偏好键，启动时一次性迁入文件。 */
const LEGACY_KEYS = [
  'butvan.featurePreferences',
  'butvan.calendarStickyNotes.expansion',
  'butvan.sidebarWidth',
  'butvan.studyWidgetPosition',
  'butvan.slashCommandPreferences',
  'butvan-record-summary-copy',
] as const;

/**
 * 启动时预加载偏好文件，并把仅存在于 localStorage 的旧值写回文件。
 * 必须在渲染应用前完成，保证同步读取接口已有数据。
 */
export async function initDesktopPreferences(): Promise<void> {
  if (initialized) return;
  initialized = true;
  if (!isTauri()) return;
  try {
    const stored = await invoke<Record<string, string>>('load_desktop_preferences');
    Object.entries(stored).forEach(([key, value]) => cache.set(key, value));
  } catch (error) {
    console.warn('桌面偏好读取失败，暂用本机缓存', error);
  }
  await migrateLegacyPreferences();
}

async function migrateLegacyPreferences(): Promise<void> {
  for (const key of LEGACY_KEYS) {
    if (cache.has(key)) continue;
    let legacy: string | null = null;
    try {
      legacy = localStorage.getItem(key);
    } catch {
      legacy = null;
    }
    if (legacy === null) continue;
    cache.set(key, legacy);
    try {
      await invoke('save_desktop_preference', { key, value: legacy });
    } catch (error) {
      console.warn('旧偏好迁移失败，将在下次写入时重试', key, error);
    }
  }
}

/** 同步读取偏好：文件缓存优先，其次旧 localStorage 数据。 */
export function readPreference(key: string): string | null {
  const cached = cache.get(key);
  if (cached !== undefined) return cached;
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/** 写入偏好：localStorage 保持同步缓存，文件为权威存储，失败只记录日志。 */
export function writePreference(key: string, value: string): void {
  cache.set(key, value);
  try {
    localStorage.setItem(key, value);
  } catch {
    // localStorage 只是缓存，写入失败不影响文件持久化。
  }
  if (!isTauri()) return;
  void invoke('save_desktop_preference', { key, value }).catch((error) => {
    console.warn('桌面偏好保存失败', key, error);
  });
}
