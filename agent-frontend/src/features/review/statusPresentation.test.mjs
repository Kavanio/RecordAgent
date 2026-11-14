import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { loadModule, startHarness, stopHarness } from '../../testing/harness.mjs';

let mod;
before(async () => {
  await startHarness();
  mod = await loadModule('/src/features/review/statusPresentation.ts');
});
after(stopHarness);

test('groupReviewDays 按归属日和版本倒序聚合，并提取摘要', () => {
  const days = mod.groupReviewDays([
    { id: '1', day: '2026-11-10', version: 1, state: 'READY', items: [{ kind: 'FACT_OBSERVATION', text: '旧版回顾', evidenceIds: [], uncertainty: '' }], message: '' },
    { id: '2', day: '2026-11-12', version: 1, state: 'DEGRADED', items: [], message: '超时未生成' },
    { id: '3', day: '2026-11-10', version: 2, state: 'READY', items: [{ kind: 'ACTION', text: '建议：早点休息', evidenceIds: [], uncertainty: '' }], message: '' },
  ]);
  assert.deepEqual(days.map(d => d.day), ['2026-11-12', '2026-11-10']);
  assert.deepEqual(days[1].versions.map(v => v.version), [2, 1]);
  assert.equal(mod.reviewExcerpt(days[0].latest), '超时未生成');
  assert.equal(mod.reviewExcerpt(days[1].latest), '早点休息');
});

test('buildOutreachTimeline 合并问候、提醒与候选并按时间倒序排列，并去除重复标题前缀', () => {
  const entries = mod.buildOutreachTimeline(
    [
      { id: 'n1', kind: 'IDLE_CHECKIN', text: '好久不见', state: 'SENT', createdAt: '2026-11-10T08:00:00Z', sentAt: null, feedback: '' },
      { id: 'n2', kind: 'WEATHER_ALERT', text: '【天气变化提醒】合肥当前转为雾', state: 'UNKNOWN', createdAt: '2026-11-12T08:00:00Z', sentAt: '2026-11-12T08:05:00Z', feedback: 'HELPFUL' },
    ],
    [
      { id: 'c1', topic: 'review-2026-11-11', sourceId: 's', contentId: 'r', state: 'PENDING', dueAt: '2026-11-11T12:00:00Z', expiresAt: '2026-11-12T12:00:00Z', feedback: '', eventKind: 'REVIEW' },
    ],
  );
  assert.deepEqual(entries.map(e => [e.id, e.category]), [
    ['n2', 'alert'],
    ['c1', 'candidate'],
    ['n1', 'greeting'],
  ]);
  assert.equal(entries[0].text, '合肥当前转为雾');
  assert.equal(entries[0].stateLabel, '结果未知');
  assert.equal(mod.cleanOutreachText('【服务器异常提醒】CPU 飙高', '服务器状态提醒'), 'CPU 飙高');
  assert.equal(mod.cleanOutreachText('【备注】保持关注', '天气变化提醒'), '【备注】保持关注');
});

test('isBusyHour 支持跨零点窗口，rhythmBars 输出 24 根柱子', () => {
  assert.equal(mod.isBusyHour(23, 22, 6), true);
  assert.equal(mod.isBusyHour(2, 22, 6), true);
  assert.equal(mod.isBusyHour(10, 22, 6), false);
  const counts = Array(24).fill(0);
  counts[21] = 10;
  counts[22] = 5;
  const bars = mod.rhythmBars(counts, 9, 18);
  assert.equal(bars.length, 24);
  assert.equal(bars[21].ratio, 1);
  assert.equal(bars[22].ratio, 0.5);
  assert.equal(bars[10].busy, true);
  assert.equal(bars[21].busy, false);
});

test('companionPulse 按优先级输出状态文案', () => {
  const cog = { backgroundAndPreferences: '', routineAndBusyHours: '', activeFocusAndBlockers: '', pendingHooksAndCuriosities: '', busyStartHour: 9, busyEndHour: 18, updatedAt: null };
  assert.equal(mod.companionPulse(false, { companionEnabled: false, unansweredCount: 1, busyDeferred: true, lastOutreachHook: '', lastLearnedAt: null, lastDataFingerprint: '', lastOutreachAt: null, lastUserActiveAt: null, nextReachAt: null }, cog, 'Asia/Shanghai').tone, 'off');
  assert.match(mod.companionPulse(true, { companionEnabled: true, unansweredCount: 1, busyDeferred: true, lastOutreachHook: '', lastLearnedAt: null, lastDataFingerprint: '', lastOutreachAt: null, lastUserActiveAt: null, nextReachAt: null }, cog, 'Asia/Shanghai').text, /忙时免打扰/);
  assert.match(mod.companionPulse(true, { companionEnabled: true, unansweredCount: 2, busyDeferred: false, lastOutreachHook: '', lastLearnedAt: null, lastDataFingerprint: '', lastOutreachAt: null, lastUserActiveAt: null, nextReachAt: null }, cog, 'Asia/Shanghai').text, /连续 2 次/);
});

test('formatCompanionChannel 与 formatRhythmSummary 去除技术冗词并保留详述', () => {
  assert.equal(mod.formatCompanionChannel('已配置的本人飞书私聊', true), '飞书私聊');
  assert.equal(mod.formatCompanionChannel('', false), '仅桌面');

  const raw = '近 14 天统计到 1 个活跃日、共 2 次互动时间戳（需至少 3 个活跃日且 12—23 点不少于 5 次互动才生成作息建议）。';
  const sparse = mod.formatRhythmSummary({ available: false, suggestedHour: 21, sampleCount: 2, activeDays: 1, windowDays: 14, hourCounts: [], note: raw });
  assert.doesNotMatch(sparse.text, /需至少/);
  assert.match(sparse.text, /2 次互动/);
  assert.equal(sparse.detail, raw);

  const ready = mod.formatRhythmSummary({ available: true, suggestedHour: 22, sampleCount: 8, activeDays: 4, windowDays: 14, hourCounts: [], note: '完整建议说明' });
  assert.match(ready.text, /22:00/);
  assert.equal(ready.detail, '完整建议说明');
});

test('zonedClock 按指定时区计算时刻指针与 24 小时进度比例', () => {
  const fixed = new Date('2026-11-14T02:57:00Z');
  const shanghai = mod.zonedClock('Asia/Shanghai', fixed);
  assert.equal(shanghai.hour, 10);
  assert.equal(shanghai.minute, 57);
  assert.equal(shanghai.label, '10:57');
  assert.ok(shanghai.fraction > 0.45 && shanghai.fraction < 0.46);
});
