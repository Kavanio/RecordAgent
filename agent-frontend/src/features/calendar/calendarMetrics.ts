import type { DailyDaySummary } from '../../types/dailyEvent';

export type CalendarMetricTone = 'todo' | 'todoDone' | 'income' | 'expense' | 'record' | 'token';

export interface CalendarCellMetric {
  id: 'todo' | 'income' | 'expense' | 'record' | 'token';
  label: string;
  value: string;
  title: string;
  tone: CalendarMetricTone;
}

export function formatCompactMoney(amount: number): string {
  if (!Number.isFinite(amount) || amount <= 0) return '0';
  const rounded = Math.round(amount * 100) / 100;
  if (Number.isInteger(rounded)) return String(rounded);
  return rounded.toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
}

export function formatCompactChars(chars: number): string {
  if (!Number.isFinite(chars) || chars <= 0) return '0字';
  if (chars >= 10000) {
    const wan = Math.round((chars / 10000) * 10) / 10;
    return `${wan}万字`;
  }
  return `${Math.round(chars)}字`;
}

export function formatCompactTokens(tokens: number): string {
  if (!Number.isFinite(tokens) || tokens <= 0) return '0';
  if (tokens >= 1_000_000) {
    const millions = Math.round((tokens / 1_000_000) * 10) / 10;
    return `${millions}M`;
  }
  if (tokens >= 10_000) {
    return `${Math.round(tokens / 1_000)}K`;
  }
  if (tokens >= 1_000) {
    const thousands = Math.round((tokens / 1_000) * 10) / 10;
    return `${thousands}K`;
  }
  return String(Math.round(tokens));
}

/** 构造月历单格极简核心指标：待办、收入、支出、资料累计与 Token 消耗。 */
export function calendarCellMetrics(
  summary: DailyDaySummary | undefined,
  recordCount: number,
  recordChars: number,
  tokenTotal: number,
): CalendarCellMetric[] {
  const metrics: CalendarCellMetric[] = [];

  if (summary && summary.todoCount > 0) {
    const allDone = summary.completedTodoCount >= summary.todoCount;
    metrics.push({
      id: 'todo',
      label: '待办',
      value: `${summary.completedTodoCount}/${summary.todoCount}`,
      title: `待办：已完成 ${summary.completedTodoCount} / 共 ${summary.todoCount}`,
      tone: allDone ? 'todoDone' : 'todo',
    });
  }

  const income = summary?.incomeTotal ?? 0;
  if (income > 0) {
    metrics.push({
      id: 'income',
      label: '收入',
      value: `+${formatCompactMoney(income)}`,
      title: `收入：¥${income.toFixed(2)}`,
      tone: 'income',
    });
  }

  const expense = summary?.expenseTotal ?? 0;
  if (expense > 0) {
    metrics.push({
      id: 'expense',
      label: '支出',
      value: `-${formatCompactMoney(expense)}`,
      title: `支出：¥${expense.toFixed(2)}`,
      tone: 'expense',
    });
  }

  if (recordChars > 0 || recordCount > 0) {
    const value = recordChars > 0 ? formatCompactChars(recordChars) : `${recordCount}篇`;
    metrics.push({
      id: 'record',
      label: '资料',
      value,
      title: recordChars > 0
        ? `资料累计：${Math.round(recordChars)}字（${recordCount}篇）`
        : `资料累计：${recordCount}篇`,
      tone: 'record',
    });
  }

  if (tokenTotal > 0) {
    metrics.push({
      id: 'token',
      label: 'Token',
      value: formatCompactTokens(tokenTotal),
      title: `Token 累计消耗：${Math.round(tokenTotal).toLocaleString()}`,
      tone: 'token',
    });
  }

  return metrics;
}
