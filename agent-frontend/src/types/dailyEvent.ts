export interface DailyEventBase {
  id: string;
  eventDate: string;
  title: string;
  source: string;
  status: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface TodoDailyEvent extends DailyEventBase {
  eventType: 'todo';
  details: {
    time: string | null;
    priority: 'high' | 'medium' | 'low';
    completed: boolean;
    recurrence: 'none' | 'daily' | 'weekly' | 'monthly';
    recurrenceWeekday: number | null;
    recurrenceMonthDay: number | null;
    completedAt: string | null;
  };
}

export interface ScheduleDailyEvent extends DailyEventBase {
  eventType: 'schedule';
  details: { startTime: string | null; endTime: string | null; location: string | null; timezone: string };
}

export interface ExpenseDailyEvent extends DailyEventBase {
  eventType: 'expense';
  details: { category: string; note: string; amount: number; time: string; currency: string };
}

export interface IncomeDailyEvent extends DailyEventBase {
  eventType: 'income';
  details: { category: string; note: string; amount: number; time: string; currency: string };
}

export interface JournalDailyEvent extends DailyEventBase {
  eventType: 'journal';
  details: { body: string; mood: string };
}

export interface StudyDailyEvent extends DailyEventBase {
  eventType: 'study';
  details: { startedAt: string; endedAt: string | null; category: string; timezone: string };
}

export interface UnknownDailyEvent extends DailyEventBase {
  eventType: 'unknown';
  originalEventType: string;
  details: Record<string, unknown> | null;
}

export type DailyEvent =
  | TodoDailyEvent
  | ScheduleDailyEvent
  | ExpenseDailyEvent
  | IncomeDailyEvent
  | JournalDailyEvent
  | StudyDailyEvent
  | UnknownDailyEvent;

export interface DailyDay {
  date: string;
  events: DailyEvent[];
}

export interface DailyDaySummary {
  date: string;
  eventCount: number;
  todoCount: number;
  completedTodoCount: number;
  scheduleCount: number;
  expenseTotal: number;
  headline: string;
  items?: Array<{ id: string; type: string; title: string }>;
}

export interface RecurringTodoSummary {
  id: string;
  title: string;
  version: number;
  recurrence: 'weekly' | 'monthly';
  occurrenceDate: string;
  completed: boolean;
}
