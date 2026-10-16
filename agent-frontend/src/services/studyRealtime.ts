import type { StudyRealtimeEvent, StudySession } from '../types/study';
import { apiEventSourceUrl, getApiBaseUrl } from './api';

const REALTIME_REASONS = new Set(['SNAPSHOT', 'STARTED', 'FINISHED', 'CREATED', 'UPDATED', 'DELETED']);

function isStudySession(value: unknown): value is StudySession {
  if (typeof value !== 'object' || value === null) return false;
  const session = value as Record<string, unknown>;
  return typeof session.id === 'string'
    && typeof session.content === 'string'
    && typeof session.category === 'string'
    && typeof session.startedAt === 'string'
    && (typeof session.endedAt === 'string' || session.endedAt === null)
    && typeof session.timezone === 'string'
    && typeof session.source === 'string'
    && typeof session.status === 'string'
    && typeof session.durationSeconds === 'number'
    && typeof session.version === 'number';
}

function parseRealtimeEvent(event: Event): StudyRealtimeEvent {
  const payload: unknown = JSON.parse((event as MessageEvent<string>).data);
  if (typeof payload !== 'object' || payload === null) throw new Error('invalid study realtime event');
  const value = payload as Record<string, unknown>;
  if (typeof value.revision !== 'number'
    || typeof value.reason !== 'string'
    || !REALTIME_REASONS.has(value.reason)
    || (value.changedSessionId !== null && typeof value.changedSessionId !== 'string')
    || (value.activeSession !== null && !isStudySession(value.activeSession))) {
    throw new Error('invalid study realtime event');
  }
  // 兼容尚未升级、仍未提供进程标识的远程桌面后端。
  const streamId = typeof value.streamId === 'string' && value.streamId.length > 0
    ? value.streamId
    : 'legacy';
  return { ...value, streamId } as unknown as StudyRealtimeEvent;
}

/**
 * 订阅学习状态实时快照。EventSource 负责断线重连，服务端会在每次建连后重发权威快照。
 */
export function subscribeStudyRealtime(
  onEvent: (event: StudyRealtimeEvent) => void,
  onMalformedEvent?: () => void,
): () => void {
  const eventSource = new EventSource(apiEventSourceUrl(`${getApiBaseUrl()}/agent/study-sessions/stream`));
  const receive = (event: Event) => {
    try {
      onEvent(parseRealtimeEvent(event));
    } catch {
      onMalformedEvent?.();
    }
  };
  eventSource.addEventListener('snapshot', receive);
  eventSource.addEventListener('changed', receive);
  return () => eventSource.close();
}
