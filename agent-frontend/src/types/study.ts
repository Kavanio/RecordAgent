export type StudySessionStatus = 'active' | 'completed';
export type StudySource = 'project' | 'manual' | 'shortcut';

export interface StudySession {
  id: string;
  content: string;
  location?: string | null;
  category: string;
  startedAt: string;
  endedAt: string | null;
  timezone: string;
  source: StudySource;
  status: StudySessionStatus;
  durationSeconds: number;
  version: number;
}

export interface StudyDayStat {
  date: string;
  durationSeconds: number;
  sessionCount: number;
}

export interface StudyStatistics {
  from: string;
  to: string;
  totalDurationSeconds: number;
  averageDailySeconds: number;
  studyDays: number;
  sessionCount: number;
  days: StudyDayStat[];
}

export type StudyRealtimeReason = 'SNAPSHOT' | 'STARTED' | 'FINISHED' | 'CREATED' | 'UPDATED' | 'DELETED';

/** 后端学习状态 SSE 推送的权威快照。 */
export interface StudyRealtimeEvent {
  streamId: string;
  revision: number;
  reason: StudyRealtimeReason;
  changedSessionId: string | null;
  activeSession: StudySession | null;
}

export interface SaveStudySessionInput {
  content: string;
  location?: string | null;
  category: string;
  startedAt: string;
  endedAt: string;
  timezone: string;
}
