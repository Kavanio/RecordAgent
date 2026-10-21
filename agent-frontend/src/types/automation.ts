/** 用户配置的自动任务，与聊天子 Agent TaskDto 独立。 */
export interface MailTemplate {
  style: 'DIGEST';
}
export type AutomationTaskStatus = 'ENABLED' | 'PAUSED' | 'FINISHED' | 'DELETED';
/** 实时连接状态与通知投递、电脑采样结果彼此独立。 */
export type AutomationSyncStatus = 'CONNECTING' | 'LIVE' | 'RECONNECTING' | 'OFFLINE';
export interface AutomationSpec {
  title: string; content: string; kind: 'REMINDER' | 'DAILY_REPORT' | 'WEEKLY_REPORT' | 'MONTHLY_REPORT' | 'SEDENTARY';
  trigger: 'ONCE' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'INTERVAL' | 'ACTIVITY';
  timezone: string; atTime: string; onceAt: string | null; weekdays: number;
  minutes: number; breakMinutes: number; windowStart: string; windowEnd: string;
  desktop: boolean; email: boolean; confirm: boolean; sound: boolean;
  expense: boolean; todo: boolean; study: boolean;
  mailTemplate: MailTemplate;
}
export interface AutomationTask { id: string; spec: AutomationSpec; status: AutomationTaskStatus; version: number; nextAt: number | null; activeSeconds: number }
export interface AutomationRun {
  id: string; taskId: string; title: string; content: string; plannedAt: number; createdAt: number;
  source: string; status: string; desktopStatus: string; emailStatus: string; confirmation: string;
  sound: boolean; error: string;
}
export interface AutomationSnapshot {
  tasks: AutomationTask[]; pending: AutomationRun[];
  /** 可选兼容字段：仅累计时长变化，不代表执行历史发生变化。 */
  activityOnly?: boolean;
  /** 同一后端实例内递增的快照序号，防止 REST 与 SSE 跨通道乱序。 */
  sourceId?: string;
  revision?: number;
}
export interface AutomationPreview { title: string; content: string; nextAt: number | null; html: string }
export interface TaskMailSettings {
  host: string; port: number; username: string; from: string; auth: boolean; starttls: boolean;
  hasPassword: boolean; enabled: boolean; maskedRecipient: string | null; ready: boolean;
}
export type SaveTaskMailSettings = Omit<TaskMailSettings, 'hasPassword' | 'maskedRecipient' | 'ready'> & { password: string };
export interface ComputerActivity { idleSeconds: number; locked: boolean; supported: boolean }
