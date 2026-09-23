import { invoke, isTauri } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import type { AutomationRun, ComputerActivity } from '../types/automation';
export const isTaskDesktop = () => isTauri();
export interface TaskNotificationStatus {
  mode: 'NATIVE' | 'DEV_FALLBACK' | 'OTHER' | 'BROWSER';
  authorized: boolean;
  alertEnabled: boolean;
  soundEnabled: boolean;
}
export async function taskNotificationStatus(): Promise<TaskNotificationStatus> {
  if (!isTauri()) return { mode: 'BROWSER', authorized: false, alertEnabled: false, soundEnabled: false };
  return invoke<TaskNotificationStatus>('task_notification_status');
}
export async function taskActivityStatus(): Promise<ComputerActivity> {
  return isTauri() ? invoke<ComputerActivity>('task_activity_status') : { supported: false, locked: false, idleSeconds: 0 };
}
export async function listenTaskActivity(receive: (sample: ComputerActivity) => void) {
  if (!isTauri()) return () => {};
  return listen<ComputerActivity>('task-activity', event => receive(event.payload));
}
export async function deliverTaskNotification(run: AutomationRun): Promise<'SUBMITTED' | 'SKIPPED'> {
  if (!isTauri()) return 'SKIPPED';
  await invoke('send_task_notification', { title: run.title, body: run.content.slice(0, 300) || '任务已完成，请在任务页查看。', sound: run.sound });
  return 'SUBMITTED';
}
export async function checkTaskNotificationPermission(): Promise<boolean> {
  if (!isTauri()) return false;
  try {
    return (await taskNotificationStatus()).authorized;
  } catch {
    return false;
  }
}
export async function requestTaskNotificationPermission(): Promise<boolean> {
  if (!isTauri()) return false;
  return (await invoke<TaskNotificationStatus>('request_task_notification_permission')).authorized;
}
export async function syncTaskReminder(visible: boolean): Promise<void> {
  if (isTauri()) await invoke('sync_task_reminder', { visible });
}
export async function playTaskReminderSound(): Promise<void> {
  if (isTauri()) await invoke('play_task_reminder_sound');
}
export async function stopTaskReminderSound(): Promise<void> {
  if (isTauri()) await invoke('stop_task_reminder_sound');
}
export const getTaskBackground = () => isTauri() ? invoke<boolean>('task_background_enabled') : Promise.resolve(false);
export const setTaskBackground = (enabled: boolean) => invoke<void>('set_task_background', { enabled });
export const openTaskMain = () => invoke<void>('open_task_main');
