import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, jsonResponse, loadModule, mount, startHarness, stopHarness, useFetchStub } from '../../../testing/harness.mjs';

let React, LifeStatusPage, MessageProvider, stub, view, writes, state;

before(async () => {
  await startHarness();
  React = getReact();
  ({ LifeStatusPage } = await loadModule('/src/components/review/status/LifeStatusPage.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);

beforeEach(() => {
  window.localStorage.clear();
  writes = [];
  state = {
    settings: {
      revision: 1, enabled: true, sources: ['JOURNAL'], providerId: 'p1', modelName: 'm1',
      timezone: 'Asia/Shanghai', historyDays: 7, dailyLimit: 1, proactive: true, feishu: false,
      idleCheckinDays: 3, authorized: true, policyVersion: 'life-v1', channelAvailable: false, channelLabel: '未连接',
    },
    history: [
      {
        id: 'rev-1', themeId: 't1', day: '2026-11-10', timezone: 'Asia/Shanghai', version: 1, state: 'READY',
        sourceAsOf: '2026-11-10T13:00:00Z', availableAt: '2026-11-10T13:00:00Z', providerId: 'p1', modelName: 'm1',
        coverage: '窗口=2026-11-04至2026-11-10', message: '',
        materials: [{ id: 'm1', kind: 'JOURNAL', sourceId: 'j1', occurredAt: '2026-11-10T09:00:00Z', text: '手记片段测试内容', truncated: false }],
        facts: [], items: [{ kind: 'FACT_OBSERVATION', text: '11月10日第一版回顾', evidenceIds: ['m1'], uncertainty: '' }],
        questions: [], deliveries: [],
      },
      {
        id: 'rev-2', themeId: 't1', day: '2026-11-10', timezone: 'Asia/Shanghai', version: 2, state: 'READY',
        sourceAsOf: '2026-11-10T15:00:00Z', availableAt: '2026-11-10T15:00:00Z', providerId: 'p1', modelName: 'm1',
        coverage: '窗口=2026-11-04至2026-11-10', message: '',
        materials: [], facts: [], items: [{ kind: 'FACT_OBSERVATION', text: '11月10日补充第二版回顾', evidenceIds: [], uncertainty: '' }],
        questions: [], deliveries: [],
      },
    ],
    candidates: [
      { id: 'cand-1', topic: 'review-2026-11-10', sourceId: 's', contentId: 'rev-2', state: 'PENDING', dueAt: '2026-11-10T14:00:00Z', expiresAt: '2026-11-11T14:00:00Z', feedback: '', eventKind: 'REVIEW' },
    ],
    nudges: [
      { id: 'nudge-1', kind: 'IDLE_CHECKIN', text: '几天没聊近况了，顺路打声招呼', state: 'SENT', createdAt: '2026-11-11T10:00:00Z', sentAt: '2026-11-11T10:00:00Z', feedback: '' },
    ],
    habit: { revision: 1, availableHour: 21, source: '', uncertainty: '', observedAt: '', expiresAt: '', active: true },
    suggestion: {
      available: true, suggestedHour: 20, sampleCount: 12, activeDays: 5, windowDays: 14,
      hourCounts: Array.from({ length: 24 }, (_, i) => (i === 20 ? 9 : i === 21 ? 4 : 0)),
      note: '近期在 20:00 较常互动',
    },
    companion: {
      companionEnabled: true, primarySessionId: 's1', primarySessionTitle: '日常陪伴', activeModelLabel: 'test-model',
      feishuConnected: false, feishuLabel: '仅桌面',
      cognition: {
        backgroundAndPreferences: '喜欢简洁', routineAndBusyHours: '白天专心工作',
        activeFocusAndBlockers: '推进项目重构', pendingHooksAndCuriosities: '周末跑步计划',
        busyStartHour: 9, busyEndHour: 18, updatedAt: '2026-11-13T12:00:00Z',
      },
      rhythm: {
        companionEnabled: true, unansweredCount: 0, busyDeferred: false, lastOutreachHook: '',
        lastLearnedAt: '2026-11-13T12:00:00Z', lastDataFingerprint: 'fp', lastOutreachAt: null,
        lastUserActiveAt: null, nextReachAt: '2026-11-14T12:30:00Z',
      },
      pendingDesktopNotices: [],
    },
    memoryStatus: {
      sourceTotal: 6, sourceActive: 5, operations: {}, oldestPendingAt: null, pauseReasons: [],
      usage: { day: '2026-11-14', writeCalls: 0, legacyCombinedQueries: 0, statusCalls: 0, recallCalls: 0, writeLimit: 500, statusLimit: 2000, recallLimit: 200 },
      skippedToday: {},
    },
    maintenance: {
      enabled: true, currentRevision: 'r1', lastCheckedAt: null, lastResult: 'proposal_ready',
      pendingProposal: { id: 'prop-1', baseRevision: 'r1', summary: '私密提案摘要', proposedProfile: '私密提案内容', changes: [] },
    },
  };

  stub = useFetchStub((url, init) => {
    const method = init?.method ?? 'GET';
    const path = url.pathname;
    if (method !== 'GET') writes.push({ path, method, body: init?.body ? JSON.parse(init.body) : null });

    if (path.endsWith('/settings')) return jsonResponse(state.settings);
    if (path.endsWith('/habit/suggestion')) return jsonResponse(state.suggestion);
    if (path.endsWith('/habit')) {
      if (method === 'PUT') state.habit = { ...state.habit, ...JSON.parse(init.body) };
      return jsonResponse(state.habit);
    }
    if (path.endsWith('/companion/cognition') && method === 'PUT') {
      state.companion.cognition = { ...state.companion.cognition, ...JSON.parse(init.body) };
      return jsonResponse(state.companion);
    }
    if (path.endsWith('/companion')) return jsonResponse(state.companion);
    if (path.endsWith('/nudges')) return jsonResponse(state.nudges);
    if (path.endsWith('/candidates')) return jsonResponse(state.candidates);
    if (path.endsWith('/archives')) return jsonResponse([]);
    if (path.endsWith('/life-reviews')) return jsonResponse(state.history);
    if (path.endsWith('/goals')) return jsonResponse([]);
    if (path === '/agent/memory/status') return jsonResponse(state.memoryStatus);
    if (path === '/agent/personal-context') {
      return jsonResponse({
        enabled: true, maintenanceEnabled: true, profile: '私密个人画像原文', source: 'explicit', revision: 'r1',
        estimatedTokens: 8, profileTokenBudget: 300, memoryTokenBudget: 500, totalTokenBudget: 850, maxProfileChars: 12000,
      });
    }
    if (path === '/agent/personal-context/maintenance') return jsonResponse(state.maintenance);
    if (path === '/agent/personal-context/proposals/prop-1/reject') {
      state.maintenance = { ...state.maintenance, pendingProposal: null, lastResult: 'rejected' };
      return jsonResponse(state.maintenance);
    }
    return jsonResponse({ providers: [] });
  });
});

afterEach(async () => {
  await view?.unmount();
  view = null;
  stub.restore();
});

async function render(props = {}) {
  view = await mount(React.createElement(MessageProvider, null, React.createElement(LifeStatusPage, props)));
}

test('展示顶部状态胶囊并支持点击柱子设定作息时间', async () => {
  await render({ initialTab: 'today' });
  const pills = view.query('[aria-label="状态摘要"]');
  assert.match(pills.textContent, /复盘已授权/);
  assert.match(pills.textContent, /陪伴中/);
  assert.match(pills.textContent, /待确认画像建议/);
  assert.match(pills.textContent, /经历来源 6/);
  assert.doesNotMatch(document.body.textContent, /私密个人画像原文|私密提案摘要/);

  const bar20 = view.query('button[aria-label="设为每天 20:00 复盘"]');
  await act(async () => bar20.click());
  assert.ok(document.body.querySelector('[role="dialog"]'));
  const saveBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent === '保存时间');
  await act(async () => saveBtn.click());
  const habitWrite = writes.find(w => w.path.endsWith('/habit'));
  assert.equal(habitWrite.body.availableHour, 20);
});

test('点击「足迹」可打开按日抽屉切换版本与视角，并支持筛选主动联系时间线', async () => {
  await render({ initialTab: 'today' });
  await act(async () => view.query('#status-tab-timeline').click());

  assert.match(document.body.textContent, /11月10日/);
  assert.match(document.body.textContent, /2 个版本/);
  assert.match(document.body.textContent, /11月10日补充第二版回顾/);

  const dayBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent.includes('11月10日') && b.textContent.includes('2 个版本'));
  await act(async () => dayBtn.click());
  const drawer = document.body.querySelector('[role="dialog"]');
  assert.ok(drawer);
  assert.match(drawer.textContent, /11月10日补充第二版回顾/);

  const v1Btn = [...drawer.querySelectorAll('button')].find(b => b.textContent === 'v1');
  await act(async () => v1Btn.click());
  assert.match(drawer.textContent, /11月10日第一版回顾/);

  const refTab = [...drawer.querySelectorAll('[role="tab"]')].find(b => b.textContent === '参考记录');
  await act(async () => refTab.click());
  assert.match(drawer.textContent, /手记片段测试内容/);

  await act(async () => window.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' })));
  assert.equal(document.body.querySelector('[role="dialog"]'), null);

  const filterBar = view.query('[aria-label="筛选主动联系记录"]');
  const candidateFilter = [...filterBar.querySelectorAll('button')].find(b => b.textContent.includes('复盘候选'));
  await act(async () => candidateFilter.click());
  assert.match(view.query('section[aria-label="主动联系时间线"]').textContent, /2026-11-10 生活复盘/);
  assert.doesNotMatch(view.query('section[aria-label="主动联系时间线"]').textContent, /几天没聊近况了/);
});

test('点击「认知」可单独编辑某一维并保留其余维度原值，私密画像正文不在首页渲染', async () => {
  await render({ initialTab: 'insight' });
  assert.match(document.body.textContent, /喜欢简洁/);
  assert.doesNotMatch(document.body.textContent, /私密个人画像原文/);
  assert.doesNotMatch(document.body.textContent, /私密提案摘要/);

  await act(async () => view.query('button[aria-label="修改 基本背景与性格喜好"]').click());
  const input = document.body.querySelector('textarea[aria-label="基本背景与性格喜好"]');
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set.call(input, '直接简洁、少寒暄');
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
    input.dispatchEvent(new window.Event('change', { bubbles: true }));
  });
  const saveBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent === '保存系统性认知');
  await act(async () => saveBtn.click());

  const cogWrite = writes.find(w => w.path.endsWith('/companion/cognition'));
  assert.deepEqual(cogWrite.body, {
    backgroundAndPreferences: '直接简洁、少寒暄',
    routineAndBusyHours: '白天专心工作',
    activeFocusAndBlockers: '推进项目重构',
    pendingHooksAndCuriosities: '周末跑步计划',
    busyStartHour: 9,
    busyEndHour: 18,
  });
});

test('今天尚无复盘且最近一次为旧日失败记录时默认折叠，点击后才展开详情', async () => {
  state.history = [{
    id: 'rev-fail', themeId: 't1', day: '2024-01-01', timezone: 'Asia/Shanghai', version: 1, state: 'DEGRADED',
    sourceAsOf: '2024-01-01T14:00:00Z', availableAt: '2024-01-01T14:00:00Z', providerId: 'p1', modelName: 'm1',
    coverage: '', message: '模型连接或服务不可用；未自动重试，参考资料仅在桌面保留。',
    materials: [], facts: [], items: [], questions: [], deliveries: [],
  }];
  await render({ initialTab: 'today' });

  assert.match(document.body.textContent, /今天还没有复盘/);
  assert.match(document.body.textContent, /最近一次（1月1日 周一）未生成/);
  assert.doesNotMatch(document.body.textContent, /本次没有生成复盘正文/);

  const toggleBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent.includes('最近一次（1月1日 周一）未生成'));
  await act(async () => toggleBtn.click());
  assert.match(document.body.textContent, /本次没有生成复盘正文/);
});
