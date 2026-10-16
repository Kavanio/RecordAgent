import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import type { StudyRealtimeEvent } from '../types/study';

/** 将后端权威学习快照交给原生菜单栏计时器；浏览器环境不执行原生命令。 */
export function syncStudyTray(event: StudyRealtimeEvent): void {
  if (!isTauri()) return;

  const startedAtEpochMs = event.activeSession
    ? Date.parse(event.activeSession.startedAt)
    : null;
  if (startedAtEpochMs !== null && !Number.isFinite(startedAtEpochMs)) {
    console.warn('学习开始时间无效，暂不更新菜单栏计时');
    return;
  }

  void invoke('sync_study_tray', {
    streamId: event.streamId,
    revision: event.revision,
    activeStudy: event.activeSession
      ? { id: event.activeSession.id, startedAtEpochMs }
      : null,
  }).catch((error: unknown) => {
    console.warn('同步菜单栏学习计时失败', error);
  });
}

/** 菜单动作只由主窗口消费，避免学习小窗重复提交结束请求。 */
export async function listenForStudyTrayFinish(onFinish: () => void): Promise<() => void> {
  if (!isTauri() || getCurrentWindow().label !== 'main') return () => {};
  return listen('study-tray-finish-request', onFinish);
}
