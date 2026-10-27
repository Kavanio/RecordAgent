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

export type ReviewState = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'DEGRADED' | 'SKIPPED' | 'UNKNOWN';

export interface ReviewItem {
  id: string;
  topicKey: string;
  kind: 'ACTION' | 'OBSERVATION' | 'FOLLOW_UP';
  text: string;
  rationale: string;
  criterion: string;
  methodId: string;
  state: string;
  uncertainty: string;
  evidenceIds: string[];
}

export interface ReviewSummary {
  id: string;
  period: ReviewPeriod;
  from: string;
  to: string;
  state: ReviewState;
  preview: boolean;
  message: string;
  createdAt: string;
  finishedAt: string | null;
}

export interface ReviewDetail extends ReviewSummary {
  timezone: string;
  coverageNote: string;
  observations: ReviewItem[];
  actions: ReviewItem[];
  followUps: ReviewItem[];
  evidence: { id: string; domain: string; metricKey: string; value: string; unit: string; currency: string; period: string; snapshotId: string }[];
}

export interface ReviewAction {
  id: string;
  reviewId: string | null;
  topicKey: string;
  goalId: string | null;
  title: string;
  rationale: string;
  criterion: string;
  methodId: string;
  state: string;
  revision: number;
  checkOn: string | null;
  feedback: string;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewObservation {
  id: string;
  reviewId: string | null;
  topicKey: string;
  content: string;
  uncertainty: string;
  state: 'CANDIDATE' | 'CORRECTED' | 'REJECTED';
  revision: number;
  correction: string;
  createdAt: string;
  expiresAt: string;
}

export interface ReviewMemory {
  actions: ReviewAction[];
  observations: ReviewObservation[];
}

export interface SaveReviewFeedback {
  requestId: string;
  expectedRevision: number;
  decision: 'ACCEPTED' | 'DECLINED' | 'DEFERRED' | 'COMPLETED' | 'PARTIAL' | 'NOT_DONE' | 'UNKNOWN' | 'ARCHIVED';
  title?: string;
  criterion?: string;
  checkOn?: string | null;
  feedback?: string;
}
