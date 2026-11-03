import type { ChatSession } from '../../types/chat';

type TimeGroupKey = 'today' | 'yesterday' | 'last7' | 'last30' | 'older';

export const TIME_GROUP_ORDER: TimeGroupKey[] = ['today', 'yesterday', 'last7', 'last30', 'older'];

export const TIME_GROUP_LABELS: Record<TimeGroupKey, string> = {
  today: '今天',
  yesterday: '昨天',
  last7: '过去 7 天',
  last30: '过去 30 天',
  older: '更早',
};

const DAY_MS = 24 * 60 * 60 * 1000;

/** 按最近活跃时间将会话分桶（ChatGPT 式时间分组）。 */
export function getTimeGroup(timestamp: number): TimeGroupKey {
  const startOfToday = new Date();
  startOfToday.setHours(0, 0, 0, 0);
  const diffDays = Math.floor((startOfToday.getTime() - timestamp) / DAY_MS);
  if (diffDays <= 0) return 'today';
  if (diffDays === 1) return 'yesterday';
  if (diffDays < 7) return 'last7';
  if (diffDays < 30) return 'last30';
  return 'older';
}

export function sortByUpdatedDesc(a: ChatSession, b: ChatSession): number {
  return b.updatedAt - a.updatedAt;
}
