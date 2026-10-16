import { invoke, isTauri } from '@tauri-apps/api/core';
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
    revision: event.revision,
    forceSnapshot: event.reason === 'SNAPSHOT',
    activeStudy: event.activeSession
      ? { id: event.activeSession.id, startedAtEpochMs }
      : null,
  }).catch((error: unknown) => {
    console.warn('同步菜单栏学习计时失败', error);
  });
}
