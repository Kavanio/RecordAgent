import { invoke, isTauri } from '@tauri-apps/api/core';
import type { TaskReminderConfiguration, TaskReminderPreferences } from '../types/taskReminder';

export const defaultReminderPreferences: TaskReminderPreferences = {
  restLock: false, soundId: 'builtin:bell', repeatSeconds: 4,
};
export const getTaskReminderConfiguration = (): Promise<TaskReminderConfiguration> => isTauri()
  ? invoke('task_reminder_configuration')
  : Promise.resolve({ preferences: defaultReminderPreferences, sounds: [] });
export const saveTaskReminderPreferences = (preferences: TaskReminderPreferences) =>
  invoke<void>('save_task_reminder_preferences', { preferences });
export const importTaskReminderAudio = async (file: File) => {
  if (!file.size || file.size > 10 * 1024 * 1024 || !/\.(wav|mp3|m4a|ogg)$/i.test(file.name)) {
    throw new Error('请选择不超过 10 MB 的 WAV、MP3、M4A 或 OGG 音频文件');
  }
  const bytes = await file.arrayBuffer();
  const context = new AudioContext();
  try {
    const decoded = await context.decodeAudioData(bytes.slice(0));
    if (!Number.isFinite(decoded.duration) || decoded.duration <= 0) throw new Error('音频时长无效');
  } catch {
    throw new Error('当前系统无法播放此音频，请尝试 WAV 或 MP3 文件');
  } finally { await context.close(); }
  return invoke<TaskReminderConfiguration>('import_task_reminder_audio', { name: file.name, bytes: Array.from(new Uint8Array(bytes)) });
};
// 串行提交关闭与续约，避免旧 effect 的异步清理覆盖新倒计时。
let lockQueue: Promise<void> = Promise.resolve();
export function syncTaskRestLock(remainingSeconds: number, runId?: string): Promise<void> {
  const request = lockQueue.then(() => isTauri()
    ? invoke<void>('sync_task_rest_lock', { remainingSeconds: Math.max(0, Math.ceil(remainingSeconds)), runId })
    : undefined);
  lockQueue = request.catch(() => {});
  return request;
}

export function previewTaskRestLock(remainingSeconds: number): Promise<void> {
  const request = lockQueue.then(() => isTauri()
    ? invoke<void>('preview_task_rest_lock', { remainingSeconds: Math.max(0, Math.ceil(remainingSeconds)) })
    : undefined);
  lockQueue = request.catch(() => {});
  return request;
}
