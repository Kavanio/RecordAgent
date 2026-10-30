import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse } from '../../testing/harness.mjs';
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
      timezone: 'Asia/Shanghai', historyDays: 7, dailyLimit: 1, proactive: false, feishu: false, channelAvailable: false };
    else if (path.endsWith('/habit')) data = null;
    else if (path.includes('/life-reviews')) data = [];
    return jsonResponse(data);
  });
});
afterEach(async () => { await view?.unmount(); view = null; stub.restore(); });
async function render(initialTab = 'review') {
  view = await mount(React.createElement(MessageProvider, null, React.createElement(Provider, null,
    React.createElement(Settings, { onBack() {}, initialTab }))));
}
test('设置侧栏只保留生活复盘入口，不再并列统计复盘', async () => {
  await render();
  assert.equal(view.query('h1').textContent, '生活复盘');
  assert.equal(view.query('[aria-label="复盘类型"]'), null);
  assert.ok(!view.queryAll('button').some(button => button.textContent.trim() === '统计复盘'));
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
});
test('生活复盘显示完整空状态说明', async () => {
  await render();
  const candidates = view.query('section[aria-label="主动记录与反馈"]');
  assert.match(candidates.textContent, /暂无主动候选/);
  assert.match(candidates.textContent, /候选和后续反馈会记录在这里/);
  assert.ok(candidates.querySelector('[aria-hidden="true"] svg'));
});
