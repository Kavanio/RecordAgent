import type {
  LifeCandidate,
  LifeCognition,
  LifeContent,
  LifeNudge,
  LifeRhythmState,
} from '../../types/lifeReview';

/** 「状态」模块纯展示逻辑：归属日计算、按日分组、主动联系时间线合并与节律柱状图数据，不访问网络。 */

export type StatusTab = 'today' | 'insight' | 'timeline';
export const STATUS_TABS: readonly { value: StatusTab; label: string }[] = [
  { value: 'today', label: '今天' },
  { value: 'insight', label: '认知' },
  { value: 'timeline', label: '足迹' },
];
export const STATUS_TAB_PREFERENCE = 'status.activeTab';

export function isStatusTab(value: string | null): value is StatusTab {
  return value === 'today' || value === 'insight' || value === 'timeline';
}

/** 按后端配置时区计算归属日，避免用本机日期推断。 */
export function zonedDay(timezone: string, now = new Date()): string {
  let parts: Intl.DateTimeFormatPart[];
  try {
    parts = new Intl.DateTimeFormat('en', { timeZone: timezone || 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  } catch {
    parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  }
  return ['year', 'month', 'day'].map(type => parts.find(part => part.type === type)?.value || '').join('-');
}

export function zonedHour(timezone: string, now = new Date()): number {
  try {
    const value = new Intl.DateTimeFormat('en', { timeZone: timezone || 'Asia/Shanghai', hour: 'numeric', hourCycle: 'h23' }).format(now);
    const hour = Number(value);
    return Number.isFinite(hour) ? hour % 24 : now.getHours();
  } catch {
    return now.getHours();
  }
}

export function greetingFor(hour: number): string {
  if (hour < 5) return '夜深了';
  if (hour < 11) return '早上好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}

/** 中文日期标题，例如「11月14日 周五」；无效日期返回原值。 */
export function dayTitle(day: string): string {
  const [y, m, d] = day.split('-').map(Number);
  if (!y || !m || !d) return day;
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][new Date(Date.UTC(y, m - 1, d)).getUTCDay()];
  return `${m}月${d}日 ${week}`;
}

export function formatHour(hour: number): string {
  return `${String(hour).padStart(2, '0')}:00`;
}

export function sortVersions(history: LifeContent[]): LifeContent[] {
  return [...history].sort((a, b) => b.day.localeCompare(a.day) || b.version - a.version);
}

export interface ReviewDay {
  day: string;
  versions: LifeContent[];
  latest: LifeContent;
}

/** 按归属日聚合版本；日期与版本均倒序。 */
export function groupReviewDays(history: LifeContent[]): ReviewDay[] {
  const map = new Map<string, LifeContent[]>();
  for (const item of sortVersions(history)) {
    const list = map.get(item.day) ?? [];
    list.push(item);
    map.set(item.day, list);
  }
  return [...map.entries()].map(([day, versions]) => ({ day, versions, latest: versions[0] }));
}

/** 历史列表摘要：优先首条回顾，其次失败说明。 */
export function reviewExcerpt(content: LifeContent): string {
  const observation = content.items.find(item => item.kind !== 'ACTION');
  if (observation) return observation.text;
  const action = content.items.find(item => item.kind === 'ACTION');
  if (action) return action.text.replace(/^建议[：:]\s*/, '');
  if (content.state === 'PENDING' || content.state === 'RUNNING') return '正在生成复盘正文…';
  return content.message || '本次没有生成复盘正文';
}

export const REVIEW_STATE_LABELS: Record<string, string> = {
  PENDING: '等待准备', RUNNING: '正在生成', READY: '已备好', DEGRADED: '未生成', SKIPPED: '未生成', UNKNOWN: '结果未知',
};

export type OutreachCategory = 'greeting' | 'alert' | 'candidate';
export const OUTREACH_FILTERS: readonly { value: OutreachCategory | 'all'; label: string }[] = [
  { value: 'all', label: '全部' },
  { value: 'greeting', label: '问候' },
  { value: 'alert', label: '提醒' },
  { value: 'candidate', label: '复盘候选' },
];

const NUDGE_KIND_LABELS: Record<string, string> = {
  IDLE_CHECKIN: '久未互动轻声问候', WEATHER_ALERT: '天气变化提醒', WEATHER_RECOVERY: '天气恢复提醒',
  EXPENSE_ALERT: '支出变化提醒', SERVER_ALERT: '服务器状态提醒', SERVER_RECOVERY: '服务器恢复提醒', MAIL_ALERT: '重要邮件提醒',
};
const NUDGE_STATES: Record<string, string> = {
  PENDING: '等待发送', SENDING: '正在发送', SENT: '已发送', UNKNOWN: '发送结果未知（不自动重发）', FAILED: '发送未完成', CANCELLED: '已取消',
};
const CANDIDATE_STATES: Record<string, string> = {
  PENDING: '等待合适时机', PRESENTED: '已呈现', CANCELLED: '已取消', EXPIRED: '已过期',
};
export const FEEDBACK_LABELS: Record<string, string> = {
  HELPFUL: '有帮助', NOT_NEEDED: '暂不需要', NOT_RELEVANT: '不相关', CANCEL: '已取消',
};

export interface OutreachEntry {
  key: string;
  id: string;
  source: 'nudge' | 'candidate';
  category: OutreachCategory;
  title: string;
  text: string;
  stateLabel: string;
  state: string;
  at: string;
  feedback: string;
}

/** 合并问候/提醒与复盘候选，按时间倒序；无效时间排在最后。 */
export function buildOutreachTimeline(nudges: LifeNudge[], candidates: LifeCandidate[]): OutreachEntry[] {
  const nudgeEntries: OutreachEntry[] = nudges.map(n => ({
    key: `nudge-${n.id}`, id: n.id, source: 'nudge', category: n.kind === 'IDLE_CHECKIN' ? 'greeting' : 'alert',
    title: NUDGE_KIND_LABELS[n.kind] || n.kind, text: n.text, state: n.state, stateLabel: NUDGE_STATES[n.state] || n.state,
    at: n.sentAt || n.createdAt, feedback: n.feedback || '',
  }));
  const candidateEntries: OutreachEntry[] = candidates.map(c => ({
    key: `candidate-${c.id}`, id: c.id, source: 'candidate', category: 'candidate',
    title: c.topic.startsWith('review-') ? `${c.topic.slice(7)} 生活复盘` : c.topic,
    text: c.state === 'PENDING' ? '有值得呈现的新信息，等待合适时机呈现。' : '复盘候选记录，未回复不会被判为失败。',
    state: c.state, stateLabel: CANDIDATE_STATES[c.state] || c.state, at: c.dueAt, feedback: c.feedback || '',
  }));
  const time = (value: string) => { const t = new Date(value).getTime(); return Number.isFinite(t) ? t : -Infinity; };
  return [...nudgeEntries, ...candidateEntries].sort((a, b) => time(b.at) - time(a.at));
}

export function formatDateTime(value: string | null | undefined, timezone: string): string {
  if (!value) return '时间未记录';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '时间未记录';
  try {
    return date.toLocaleString('zh-CN', { timeZone: timezone || 'Asia/Shanghai', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  } catch {
    return date.toLocaleString('zh-CN');
  }
}

/** 忙碌窗口支持跨零点（例如 22—6）。 */
export function isBusyHour(hour: number, start: number, end: number): boolean {
  if (start === end) return false;
  return start < end ? hour >= start && hour < end : hour >= start || hour < end;
}

export interface RhythmBar { hour: number; count: number; ratio: number; busy: boolean }

export function rhythmBars(hourCounts: number[] | undefined, busyStart?: number, busyEnd?: number): RhythmBar[] {
  const counts = Array.from({ length: 24 }, (_, hour) => Math.max(0, Number(hourCounts?.[hour]) || 0));
  const max = Math.max(...counts, 0);
  return counts.map((count, hour) => ({
    hour, count, ratio: max > 0 ? count / max : 0,
    busy: busyStart !== undefined && busyEnd !== undefined && isBusyHour(hour, busyStart, busyEnd),
  }));
}

/** 陪伴节奏一句话说明，优先级：未开启 > 忙时顺延 > 未回复 > 下次时间 > 等待安排。 */
export function companionPulse(enabled: boolean, rhythm: LifeRhythmState, cognition: LifeCognition, timezone: string): { tone: 'off' | 'wait' | 'on'; text: string } {
  if (!enabled) return { tone: 'off', text: '主动陪伴未开启，Agent 不会主动问候你' };
  if (rhythm.busyDeferred) return { tone: 'wait', text: `忙时免打扰顺延中（${formatHour(cognition.busyStartHour)}—${formatHour(cognition.busyEndHour)}），忙完后再跟进` };
  if (rhythm.unansweredCount > 0) return { tone: 'wait', text: `上次问候暂未回复（连续 ${rhythm.unansweredCount} 次），已自动拉长间隔不催促` };
  if (rhythm.nextReachAt) return { tone: 'on', text: `下次问候预计 ${formatDateTime(rhythm.nextReachAt, timezone)}` };
  return { tone: 'on', text: '正在根据你的作息与近期动态安排时机' };
}
