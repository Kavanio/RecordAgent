import { invoke } from '@tauri-apps/api/core';

/** 打开当前桌面系统的定位隐私设置页；不接受外部 URL 或命令参数。 */
export async function openLocationPrivacySettings(): Promise<void> {
  if (typeof window === 'undefined' || !('__TAURI_INTERNALS__' in window)) {
    throw new Error('当前不是桌面应用环境，无法直接打开系统设置。');
  }
  await invoke('open_location_settings');
}
