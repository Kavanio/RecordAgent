/** 长期复盘基础契约；尚未接入模型生成和界面开关。 */
export type ReviewPeriod = 'DAILY_REPORT' | 'WEEKLY_REPORT' | 'MONTHLY_REPORT';
export type ReviewDomain = 'FINANCE' | 'TODO' | 'STUDY';

export interface SaveReviewSettings {
  expectedRevision: number;
  enabled: boolean;
  periods: ReviewPeriod[];
  domains: ReviewDomain[];
  historyDays: number;
  dailyLimit: number;
  providerId: string;
  modelName: string;
}

export interface ReviewGrant {
  id: string;
  policyVersion: string;
  state: 'ACTIVE' | 'REVOKED';
  revision: number;
  periods: ReviewPeriod[];
  domains: ReviewDomain[];
  historyDays: number;
  dailyLimit: number;
  connectionFingerprint: string;
  createdAt: string;
  revokedAt: string | null;
}

export interface ReviewSettings {
  enabled: boolean;
  authorized: boolean;
  generationAvailable: boolean;
  capability: 'FOUNDATION_ONLY';
  policyVersion: string;
  periods: ReviewPeriod[];
  domains: ReviewDomain[];
  historyDays: number;
  dailyLimit: number;
  providerId: string;
  modelName: string;
  connectionFingerprint: string;
  revision: number;
  grant: ReviewGrant | null;
}

export interface ReviewFact {
  domain: ReviewDomain;
  metricKey: string;
  subjectId: string;
  currency: string;
  unit: 'MONEY_MINOR' | 'COUNT' | 'SECONDS';
  /** 后端以十进制整数字符串传输，避免 64 位值在 JavaScript 中丢失精度。 */
  value: string;
}

export interface ReviewFactSnapshotSummary {
  id: string;
  from: string;
  to: string;
  timezone: string;
  sourceAsOf: string;
  metricVersion: number;
  fingerprint: string;
  expiresAt: string;
}

export interface ReviewFactSnapshot extends ReviewFactSnapshotSummary {
  facts: ReviewFact[];
  sources: { domain: ReviewDomain; state: 'AVAILABLE' | 'EMPTY' | 'UNAVAILABLE' }[];
}

export interface SaveReviewGoal {
  expectedRevision: number;
  title: string;
  constraintText: string;
  successCriterion: string;
  state: 'ACTIVE' | 'ARCHIVED';
}

export interface ReviewGoal {
  id: string;
  title: string;
  constraintText: string;
  successCriterion: string;
  state: 'ACTIVE' | 'ARCHIVED';
  revision: number;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewEraseResult {
  deletedSnapshots: number;
  deletedGoals: number;
  settingsRevision: number;
}
