import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { isPermissionGranted, requestPermission, sendNotification } from '@tauri-apps/plugin-notification';
import type { AutomationRun, ComputerActivity } from '../types/automation';
export const isTaskDesktop = () => isTauri();
export async function taskActivityStatus(): Promise<ComputerActivity> {
  return isTauri() ? invoke<ComputerActivity>('task_activity_status') : { supported: false, locked: false, idleSeconds: 0 };
}
export async function listenTaskActivity(receive: (sample: ComputerActivity) => void) {
  if (!isTauri()) return () => {};
  return listen<ComputerActivity>('task-activity', event => receive(event.payload));
}
export async function deliverTaskNotification(run: AutomationRun): Promise<'SUBMITTED' | 'SKIPPED'> {
  if (!isTauri()) return 'SKIPPED';
  if (!await isPermissionGranted()) throw new Error('请在任务设置中开启系统通知权限');
  if (run.sound) await invoke('play_task_sound');
  sendNotification({ title: run.title, body: run.confirmation === 'WAITING' ? run.content.slice(0, 150) : '任务已完成，请在任务历史中查看完整内容。' });
  return 'SUBMITTED';
}
export async function checkTaskNotificationPermission(): Promise<boolean> {
  if (!isTauri()) return false;
  try {
    return await isPermissionGranted();
  } catch {
    return false;
  }
}
export async function requestTaskNotificationPermission(): Promise<boolean> {
  if (!isTauri()) return false;
  return await isPermissionGranted() || await requestPermission() === 'granted';
}
export async function syncTaskReminder(visible: boolean): Promise<void> {
  if (isTauri()) await invoke('sync_task_reminder', { visible });
}
export const getTaskBackground = () => isTauri() ? invoke<boolean>('task_background_enabled') : Promise.resolve(false);
export const setTaskBackground = (enabled: boolean) => invoke<void>('set_task_background', { enabled });
export const openTaskMain = () => invoke<void>('open_task_main');
