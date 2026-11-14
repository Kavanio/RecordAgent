import type {
  LifeCandidate,
  LifeCognition,
  LifeContent,
  LifeHabitSuggestion,
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

/** 返回配置时区下的当前时分、时间文案及 24 小时进度比例（0–1），避免依赖本机时区。 */
export function zonedClock(timezone: string, now = new Date()): { hour: number; minute: number; label: string; fraction: number } {
  let parts: Intl.DateTimeFormatPart[] = [];
  try {
    parts = new Intl.DateTimeFormat('en', { timeZone: timezone || 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(now);
  } catch {
    try {
      parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(now);
    } catch { /* fall back to local Date */ }
  }
  const h = Number(parts.find(p => p.type === 'hour')?.value);
  const m = Number(parts.find(p => p.type === 'minute')?.value);
  const hour = Number.isFinite(h) ? h % 24 : now.getHours();
  const minute = Number.isFinite(m) ? m % 60 : now.getMinutes();
  return {
    hour,
    minute,
    label: `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`,
    fraction: Math.min(0.99, Math.max(0.01, (hour + minute / 60) / 24)),
  };
}

export function zonedHour(timezone: string, now = new Date()): number {
  return zonedClock(timezone, now).hour;
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
  PENDING: '等待发送', SENDING: '正在发送', SENT: '已发送', UNKNOWN: '结果未知', FAILED: '发送未完成', CANCELLED: '已取消',
};
const CANDIDATE_STATES: Record<string, string> = {
  PENDING: '等待合适时机', PRESENTED: '已呈现', CANCELLED: '已取消', EXPIRED: '已过期',
};
export const FEEDBACK_LABELS: Record<string, string> = {
  HELPFUL: '有帮助', NOT_NEEDED: '暂不需要', NOT_RELEVANT: '不相关', CANCEL: '已取消',
};

/** 去掉正文中与卡片标题完全重复的 `【标题】` 前缀。 */
export function cleanOutreachText(text: string, title: string): string {
  const prefix = `【${title}】`;
  if (text.startsWith(prefix)) return text.slice(prefix.length).trim();
  return text.replace(/^【[^】]{2,14}提醒】\s*/, '');
}

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
  const nudgeEntries: OutreachEntry[] = nudges.map(n => {
    const title = NUDGE_KIND_LABELS[n.kind] || n.kind;
    return {
      key: `nudge-${n.id}`, id: n.id, source: 'nudge', category: n.kind === 'IDLE_CHECKIN' ? 'greeting' : 'alert',
      title, text: cleanOutreachText(n.text, title), state: n.state, stateLabel: NUDGE_STATES[n.state] || n.state,
      at: n.sentAt || n.createdAt, feedback: n.feedback || '',
    };
  });
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

/** 计算免打扰忙碌时段的连续水平带（支持跨零点拆分为两段），供节律背景雾带渲染。 */
export function busySegments(start?: number, end?: number): { startPct: number; widthPct: number }[] {
  if (start === undefined || end === undefined || start === end) return [];
  if (start < end) return [{ startPct: (start / 24) * 100, widthPct: ((end - start) / 24) * 100 }];
  return [
    { startPct: (start / 24) * 100, widthPct: ((24 - start) / 24) * 100 },
    { startPct: 0, widthPct: (end / 24) * 100 },
  ];
}

export interface RhythmWaveGeometry {
  strokePath: string;
  areaPath: string;
  ambientPath: string;
  points: { x: number; y: number }[];
  hasData: boolean;
}

/** 生成 24 小时平滑贝塞尔浪潮曲线（viewBox 240×54），保持波峰不越界、零值贴合基线。 */
export function rhythmWaveGeometry(bars: RhythmBar[]): RhythmWaveGeometry {
  const baseY = 50;
  const topY = 10;
  const clampY = (y: number) => Math.max(topY - 2, Math.min(baseY, y));
  const fmt = (n: number) => Number(n.toFixed(1));
  const hasData = bars.some(bar => bar.count > 0);
  const points = bars.map((bar, index) => {
    const amp = bar.count > 0 ? Math.max(bar.ratio, 0.28) : 0;
    return { x: index * 10 + 5, y: hasData ? fmt(baseY - amp * (baseY - topY)) : baseY };
  });
  const ambientPath = 'M 0 47 Q 40 43 80 47 T 160 47 T 240 47';
  if (!hasData || points.length === 0) {
    return { strokePath: ambientPath, areaPath: `${ambientPath} L 240 50 L 0 50 Z`, ambientPath, points, hasData: false };
  }
  const nodes = [{ x: 0, y: points[0].y }, ...points, { x: 240, y: points[points.length - 1].y }];
  const segs: string[] = [`M ${nodes[0].x} ${nodes[0].y}`];
  for (let i = 0; i < nodes.length - 1; i += 1) {
    const p0 = nodes[Math.max(0, i - 1)];
    const p1 = nodes[i];
    const p2 = nodes[i + 1];
    const p3 = nodes[Math.min(nodes.length - 1, i + 2)];
    const cx1 = fmt(p1.x + (p2.x - p0.x) / 6);
    const cy1 = fmt(clampY(p1.y + (p2.y - p0.y) / 6));
    const cx2 = fmt(p2.x - (p3.x - p1.x) / 6);
    const cy2 = fmt(clampY(p2.y - (p3.y - p1.y) / 6));
    segs.push(`C ${cx1} ${cy1}, ${cx2} ${cy2}, ${p2.x} ${p2.y}`);
  }
  const strokePath = segs.join(' ');
  return { strokePath, areaPath: `${strokePath} L 240 ${baseY} L 0 ${baseY} Z`, ambientPath, points, hasData: true };
}

/** 将后端渠道说明收敛为适合仪表盘的极简短标签，保留原值作悬浮详情。 */
export function formatCompanionChannel(label: string | undefined, connected: boolean): string {
  const raw = (label || '').trim();
  if (!raw) return connected ? '飞书私聊' : '仅桌面';
  return raw.replace(/^已配置的本人/, '').replace(/^已配置的/, '') || (connected ? '飞书私聊' : '仅桌面');
}

/** 提炼作息建议的人话摘要，将技术阈值说明收进悬浮 detail。 */
export function formatRhythmSummary(suggestion: LifeHabitSuggestion | null): { text: string; detail: string } {
  const rawNote = suggestion?.note || '';
  if (suggestion?.available) {
    return {
      text: `近期在 ${suggestion.suggestedHour}:00 前后较常互动`,
      detail: rawNote,
    };
  }
  if (suggestion && suggestion.sampleCount > 0) {
    return {
      text: `近 ${suggestion.windowDays} 天记录 ${suggestion.sampleCount} 次互动，点 12–23 时柱可设时间`,
      detail: rawNote || '积累更多晚间互动后将自动推荐最佳时间。',
    };
  }
  return {
    text: '点选 12–23 时柱子可调整每日复盘时间',
    detail: rawNote || '仅统计近 14 天消息时间戳，不读取正文。',
  };
}

/** 陪伴节奏一句话说明，优先级：未开启 > 忙时顺延 > 未回复 > 下次时间 > 等待安排。 */
export function companionPulse(enabled: boolean, rhythm: LifeRhythmState, cognition: LifeCognition, timezone: string): { tone: 'off' | 'wait' | 'on'; text: string } {
  if (!enabled) return { tone: 'off', text: '主动陪伴未开启' };
  if (rhythm.busyDeferred) return { tone: 'wait', text: `忙时免打扰顺延中（${formatHour(cognition.busyStartHour)}—${formatHour(cognition.busyEndHour)}）` };
  if (rhythm.unansweredCount > 0) return { tone: 'wait', text: `连续 ${rhythm.unansweredCount} 次未回，已拉长跟进间隔` };
  if (rhythm.nextReachAt) return { tone: 'on', text: `下次问候预计 ${formatDateTime(rhythm.nextReachAt, timezone)}` };
  return { tone: 'on', text: '正在安排合适问候时机' };
}
