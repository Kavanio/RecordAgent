import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';
let React, Settings, Provider, MessageProvider, view, stub, calls;
before(async () => {
  await startHarness(); React = getReact();
  ({ ModelSettingsPage: Settings } = await loadModule('/src/components/model/ModelSettingsPage.tsx'));
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
      timezone: 'Asia/Shanghai', historyDays: 7, dailyLimit: 1, proactive: false, feishu: false, channelAvailable: false,
      periods: ['DAILY_REPORT'], domains: ['STUDY'], grant: null };
    else if (path.endsWith('/memory')) data = { actions: [], observations: [] };
    else if (path.endsWith('/habit')) data = null;
    else if (path.includes('/life-reviews') || path.includes('/agent/reviews')) data = [];
    return jsonResponse(data);
  });
});
afterEach(async () => { await view?.unmount(); view = null; stub.restore(); });
async function render(initialTab = 'review') {
  view = await mount(React.createElement(MessageProvider, null, React.createElement(Provider, null,
    React.createElement(Settings, { onBack() {}, initialTab }))));
}
async function click(label) {
  const node = view.queryAll('button').find(b => b.textContent.trim() === label);
  assert.ok(node, label); await act(async () => node.click());
}
test('生活与统计复盘从设置侧栏切换，页面顶部不再叠加Tab栏', async () => {
  await render();
  assert.equal(view.query('h1').textContent, '生活复盘');
  assert.equal(view.query('[aria-label="复盘类型"]'), null);
  await click('统计复盘');
  assert.equal(view.query('h1').textContent, '统计复盘');
  assert.equal(view.queryAll('h1').length, 1);
  await click('生活复盘');
  assert.equal(view.query('h1').textContent, '生活复盘');
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
});
test('两类复盘都显示完整空状态说明，已有统计入口可直接进入', async () => {
  await render();
  const candidates = view.query('section[aria-label="主动记录与反馈"]');
  assert.match(candidates.textContent, /暂无主动候选/);
  assert.match(candidates.textContent, /候选和后续反馈会记录在这里/);
  assert.ok(candidates.querySelector('[aria-hidden="true"] svg'));
  await click('统计复盘');
  const suggestions = view.query('section[aria-label="建议与观察"]');
  assert.match(suggestions.textContent, /还没有建议/);
  assert.match(suggestions.textContent, /可以在这里查看并反馈/);
});
