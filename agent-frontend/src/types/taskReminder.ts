/** 本机提醒偏好；与服务器任务的声音开关独立，下一次提醒开始时读取。 */
export interface TaskReminderPreferences {
  restLock: boolean;
  soundId: string;
  repeatSeconds: number;
}

export interface TaskReminderSound {
  id: string;
  name: string;
  source: 'builtin' | 'system' | 'custom';
}

export interface TaskReminderConfiguration {
  preferences: TaskReminderPreferences;
  sounds: TaskReminderSound[];
}
