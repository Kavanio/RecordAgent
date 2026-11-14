import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';
let React, Settings, StatusPage, Provider, MessageProvider, view, stub, calls;
before(async () => {
  await startHarness(); React = getReact();
  ({ ModelSettingsPage: Settings } = await loadModule('/src/components/model/ModelSettingsPage.tsx'));
  ({ LifeStatusPage: StatusPage } = await loadModule('/src/components/review/status/LifeStatusPage.tsx'));
  ({ ModelProviderContext: Provider } = await loadModule('/src/context/ModelContext.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);
beforeEach(() => {
  calls = [];
  stub = useFetchStub((url, init) => {
    calls.push({ url, init });
    const path = url.pathname;
    let data = {};
    if (path.endsWith('/full-config')) data = { providers: [], activeVendor: '', activeModel: '', apiKey: '', temperature: 0.7 };
    else if (path.endsWith('/vendors')) data = ['custom'];
    else if (path.endsWith('/settings')) data = { revision: 1, enabled: false, authorized: false, sources: [], providerId: '', modelName: '',
      timezone: 'Asia/Shanghai', historyDays: 7, dailyLimit: 1, proactive: false, feishu: false, channelAvailable: false };
    else if (path.endsWith('/habit')) data = null;
    else if (path.endsWith('/goals')) data = [];
    else if (path === '/agent/personal-context') data = { enabled: true, maintenanceEnabled: false, profile: '我的画像', source: 'explicit',
      revision: 'r1', estimatedTokens: 4, profileTokenBudget: 300, memoryTokenBudget: 500, totalTokenBudget: 850, maxProfileChars: 12000 };
    else if (path === '/agent/personal-context/maintenance') data = { enabled: false, currentRevision: 'r1', lastCheckedAt: null, lastResult: 'never_checked', pendingProposal: null };
    else if (path.includes('/life-reviews')) data = [];
    return jsonResponse(data);
  });
});
afterEach(async () => { await view?.unmount(); view = null; stub.restore(); });
async function renderSettings(initialTab = 'review', onOpenStatus = () => {}) {
  view = await mount(React.createElement(MessageProvider, null, React.createElement(Provider, null,
    React.createElement(Settings, { onBack() {}, initialTab, onOpenStatus }))));
}
async function renderStatus(onOpenSettings = () => {}, initialTab = 'today') {
  view = await mount(React.createElement(MessageProvider, null, React.createElement(StatusPage, { onOpenSettings, initialTab })));
}
test('设置侧栏生活复盘专注配置与授权，并支持一键前往状态模块', async () => {
  let openedStatus = false;
  await renderSettings('review', () => { openedStatus = true; });
  assert.equal(view.query('h1').textContent, '生活复盘');
  assert.equal(view.query('[aria-label="复盘类型"]'), null);
  assert.ok(!view.queryAll('button').some(button => button.textContent.trim() === '统计复盘'));
  assert.equal(view.query('section[aria-label="个人目标"]'), null);
  const goBtn = view.queryAll('button').find(button => button.textContent.trim() === '前往状态');
  assert.ok(goBtn);
  await act(async () => goBtn.click());
  assert.equal(openedStatus, true);
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
});
test('状态模块整合今天/认知/足迹三分段及复盘设置跳转', async () => {
  let openedTab = '';
  await renderStatus((tab) => { openedTab = tab; });
  assert.equal(view.query('h1').textContent, '状态');
  assert.ok(view.query('section[aria-label="今天的复盘"]'));
  assert.ok(view.query('section[aria-label="个人目标"]'));
  assert.ok(view.query('section[aria-label="作息节律"]'));

  const insightTab = view.query('#status-tab-insight');
  assert.ok(insightTab);
  await act(async () => insightTab.click());
  assert.ok(view.query('section[aria-label="个人画像"]'));
  assert.ok(view.query('section[aria-label="经历来源管理"]'));

  const timelineTab = view.query('#status-tab-timeline');
  assert.ok(timelineTab);
  await act(async () => timelineTab.click());
  const outreach = view.query('section[aria-label="主动联系时间线"]');
  assert.match(outreach.textContent, /暂无主动联系或候选记录/);
  assert.ok(outreach.querySelector('[aria-hidden="true"] svg'));

  const todayTab = view.query('#status-tab-today');
  await act(async () => todayTab.click());
  const jumpBtn = view.queryAll('button').find(button => button.textContent.trim() === '前往复盘设置');
  assert.ok(jumpBtn);
  await act(async () => jumpBtn.click());
  assert.equal(openedTab, 'review');
});
