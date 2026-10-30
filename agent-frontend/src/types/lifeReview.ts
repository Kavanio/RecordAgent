/** 生活复盘独立授权与不可变版本 DTO；不复用统计复盘许可。 */
export type LifeSource = 'BUSINESS' | 'JOURNAL' | 'CHAT' | 'FINANCE' | 'GOALS';
export interface LifeSettings {
  revision: number; enabled: boolean; sources: LifeSource[]; providerId: string; modelName: string;
  timezone: string; historyDays: number; dailyLimit: number; proactive: boolean; feishu: boolean;
  authorized: boolean; policyVersion: string; channelAvailable: boolean; channelLabel: string;
}
export interface LifeContent {
  id: string; themeId: string; day: string; timezone: string; version: number; state: string; sourceAsOf: string;
  availableAt: string; providerId: string; modelName: string; coverage: string; message: string;
  materials: { id: string; kind: string; sourceId: string; occurredAt: string; text: string; truncated: boolean }[];
  facts: { id: string; domain: string; metric: string; value: string; unit: string; currency: string; period: string }[];
  items: { kind: string; text: string; evidenceIds: string[]; uncertainty: string }[];
  questions: string[]; deliveries: { id: string; channel: string; state: string; attempts: number }[];
}
export interface LifeCandidate {
  id: string; topic: string; sourceId: string; contentId: string; state: string;
  dueAt: string; expiresAt: string; feedback: string; eventKind: 'REVIEW' | 'EVENT' | 'RECOVERY';
}
export interface LifeHabit {
  revision: number; availableHour: number; source: string; uncertainty: string;
  observedAt: string; expiresAt: string; active: boolean;
}
