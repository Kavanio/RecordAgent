import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  calendarCellMetrics,
  formatCompactChars,
  formatCompactMoney,
  formatCompactTokens,
} from './calendarMetrics.ts';

test('月历单格核心指标格式化与过滤零值', () => {
  const metrics = calendarCellMetrics(
    {
      date: '2026-11-20',
      eventCount: 5,
      todoCount: 4,
      completedTodoCount: 3,
      scheduleCount: 0,
      expenseTotal: 12,
      incomeTotal: 30,
      headline: '',
    },
    2,
    1200,
    1_000_000,
  );

  assert.deepEqual(
    metrics.map((m) => [m.label, m.value]),
    [
      ['待办', '3/4'],
      ['财务', '30/12元'],
      ['资料', '1200字'],
      ['Token', '1M'],
    ],
  );
});

test('无数据日期不输出零值指标，且兼容旧后端缺失字数与收入字段', () => {
  assert.deepEqual(calendarCellMetrics(undefined, 0, 0, 0), []);
  const fallback = calendarCellMetrics(
    {
      date: '2026-11-18',
      eventCount: 1,
      todoCount: 0,
      completedTodoCount: 0,
      scheduleCount: 0,
      expenseTotal: 27.5,
      headline: '',
    },
    1,
    0,
    4820,
  );
  assert.deepEqual(
    fallback.map((m) => [m.label, m.value]),
    [
      ['财务', '0/27.5元'],
      ['资料', '1篇'],
      ['Token', '4.8K'],
    ],
  );
  assert.equal(formatCompactMoney(30.0), '30');
  assert.equal(formatCompactMoney(27.5), '27.5');
  assert.equal(formatCompactChars(12500), '1.3万字');
  assert.equal(formatCompactTokens(25400), '25K');
  assert.equal(formatCompactTokens(850), '850');
});
