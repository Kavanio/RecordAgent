import type { CalendarJournal, CalendarRecordDraft } from '../types/calendar';
import type {
  DailyDay,
  DailyDaySummary,
  DailyEvent,
  DailyEventBase,
  ExpenseDailyEvent,
  IncomeDailyEvent,
  JournalDailyEvent,
  ScheduleDailyEvent,
  StudyDailyEvent,
  TodoDailyEvent,
  RecurringTodoSummary,
} from '../types/dailyEvent';
import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';

type RawEvent = DailyEventBase & { eventType: string; details: unknown };
type RawDay = { date: string; events: RawEvent[] };

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null;

/** 使用本地年月日生成接口日期，避免 UTC 转换导致日期偏移。 */
export function formatLocalDate(date: Date): string {
  return [date.getFullYear(), date.getMonth() + 1, date.getDate()]
    .map((value, index) => index === 0 ? String(value) : String(value).padStart(2, '0'))
    .join('-');
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}${path}`, init);
  let payload: ApiResponse<T>;
  try {
    payload = await response.json() as ApiResponse<T>;
  } catch {
    throw new Error(`日记录服务返回了无法识别的响应（HTTP ${response.status}）`);
  }
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.message || `日记录请求失败（HTTP ${response.status}）`);
  }
  return payload.data;
}

function parseEvent(raw: RawEvent): DailyEvent {
  const common: DailyEventBase = {
    id: raw.id,
    eventDate: raw.eventDate,
    title: raw.title,
    source: raw.source,
    status: raw.status,
    version: raw.version,
    createdAt: raw.createdAt,
    updatedAt: raw.updatedAt,
  };
  const details = isRecord(raw.details) ? raw.details : {};

  if (raw.eventType === 'todo') {
    return { ...common, eventType: 'todo', details: {
      time: typeof details.time === 'string' ? details.time : null,
      priority: details.priority === 'high' || details.priority === 'low' ? details.priority : 'medium',
      completed: details.completed === true,
      recurrence: details.recurrence === 'daily' || details.recurrence === 'weekly' || details.recurrence === 'monthly'
        ? details.recurrence
        : 'none',
      recurrenceWeekday: typeof details.recurrenceWeekday === 'number' ? details.recurrenceWeekday : null,
      recurrenceMonthDay: typeof details.recurrenceMonthDay === 'number' ? details.recurrenceMonthDay : null,
      completedAt: typeof details.completedAt === 'string' ? details.completedAt : null,
    } } satisfies TodoDailyEvent;
  }
  if (raw.eventType === 'schedule') {
    return { ...common, eventType: 'schedule', details: {
      startTime: typeof details.startTime === 'string' ? details.startTime : null,
      endTime: typeof details.endTime === 'string' ? details.endTime : null,
      location: typeof details.location === 'string' ? details.location : null,
      timezone: String(details.timezone ?? ''),
    } } satisfies ScheduleDailyEvent;
  }
  if (raw.eventType === 'expense') {
    return { ...common, eventType: 'expense', details: {
      category: String(details.category ?? ''),
      note: String(details.note ?? ''),
      amount: Number(details.amount ?? 0),
      time: String(details.time ?? ''),
      currency: String(details.currency ?? 'CNY'),
    } } satisfies ExpenseDailyEvent;
  }
  if (raw.eventType === 'income') {
    return { ...common, eventType: 'income', details: {
      category: String(details.category ?? ''),
      note: String(details.note ?? ''),
      amount: Number(details.amount ?? 0),
      time: String(details.time ?? ''),
      currency: String(details.currency ?? 'CNY'),
    } } satisfies IncomeDailyEvent;
  }
  if (raw.eventType === 'journal') {
    return { ...common, eventType: 'journal', details: {
      body: String(details.body ?? ''),
      mood: String(details.mood ?? ''),
    } } satisfies JournalDailyEvent;
  }
  if (raw.eventType === 'study') {
    return { ...common, eventType: 'study', details: {
      startedAt: String(details.startedAt ?? ''),
      endedAt: typeof details.endedAt === 'string' ? details.endedAt : null,
      category: String(details.category ?? '其他'),
      timezone: String(details.timezone ?? ''),
    } } satisfies StudyDailyEvent;
  }
  return { ...common, eventType: 'unknown', originalEventType: raw.eventType, details };
}

/** 查询日期范围摘要。 */
export async function fetchDailySummaries(from: Date, to: Date): Promise<DailyDaySummary[]> {
  return request<DailyDaySummary[]>(
    `/agent/daily-events/days?from=${formatLocalDate(from)}&to=${formatLocalDate(to)}`,
  );
}

/** 查询某日完整日记录。 */
export async function fetchDailyDay(date: Date): Promise<DailyDay> {
  const raw = await request<RawDay>(`/agent/daily-events/days/${formatLocalDate(date)}`);
  return { date: raw.date, events: raw.events.map(parseEvent) };
}

/** 查询所选日期所在自然周和自然月的周期待办便签数据。 */
export async function fetchRecurringTodos(date: Date): Promise<RecurringTodoSummary[]> {
  return request<RecurringTodoSummary[]>(
    `/agent/daily-events/recurring-todos?date=${formatLocalDate(date)}`,
  );
}

/** 创建当前日历支持的一种日记录。 */
export async function createDailyRecord(date: Date, draft: CalendarRecordDraft): Promise<DailyEvent> {
  const eventDate = formatLocalDate(date);
  const base = { method: 'POST', headers: { 'Content-Type': 'application/json' } };
  let path: string;
  let body: object;
  if (draft.kind === 'todo') {
    path = '/agent/daily-events/todos';
    body = {
      eventDate,
      title: draft.title,
      time: draft.time,
      priority: draft.priority,
      recurrence: draft.recurrence,
      recurrenceWeekday: draft.recurrenceWeekday,
      recurrenceMonthDay: draft.recurrenceMonthDay,
    };
  } else if (draft.kind === 'schedule') {
    path = '/agent/daily-events/schedules';
    body = { ...draft, eventDate, timezone: Intl.DateTimeFormat().resolvedOptions().timeZone };
  } else if (draft.kind === 'expense') {
    path = '/agent/daily-events/expenses';
    body = { ...draft, eventDate, currency: 'CNY' };
  } else {
    path = '/agent/daily-events/journals';
    body = { eventDate, title: draft.title, body: draft.excerpt, mood: draft.mood };
  }
  const raw = await request<RawEvent>(path, { ...base, body: JSON.stringify(body) });
  return parseEvent(raw);
}

/** 覆盖保存已有手记。 */
export async function updateDailyJournal(
  date: Date,
  journal: CalendarJournal,
): Promise<DailyEvent> {
  if (!journal.id || journal.version === undefined) throw new Error('缺少手记版本，无法安全更新');
  const raw = await request<RawEvent>(
    `/agent/daily-events/journals/${journal.id}?expectedVersion=${journal.version}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        eventDate: formatLocalDate(date),
        title: journal.title,
        body: journal.excerpt,
        mood: journal.mood,
      }),
    },
  );
  return parseEvent(raw);
}

/** 修改待办完成状态。 */
export async function setDailyTodoCompleted(
  id: string,
  completed: boolean,
  expectedVersion: number,
  occurrenceDate: Date,
): Promise<DailyEvent> {
  const raw = await request<RawEvent>(`/agent/daily-events/${id}/todo-completion`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ completed, expectedVersion, occurrenceDate: formatLocalDate(occurrenceDate) }),
  });
  return parseEvent(raw);
}

/** 按版本删除一条日记录，避免并发编辑时误删新内容。 */
export async function deleteDailyEvent(id: string, expectedVersion: number): Promise<void> {
  await request<string>(
    `/agent/daily-events/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`,
    { method: 'DELETE' },
  );
}
