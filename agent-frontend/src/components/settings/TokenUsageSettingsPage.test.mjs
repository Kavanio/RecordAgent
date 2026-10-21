import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, server, React, createRoot, TokenUsageSettingsPage, SettingsMainRegion, root, container, respond;
const originalFetch = globalThis.fetch;
const response = data => ({ ok: true, json: async () => ({ code: 200, data }) });
const overview = (total = 123) => ({
  from: null, to: null, sessionId: null,
  totals: { inputTokens: total, outputTokens: 0, cachedInputTokens: 0, totalTokens: total, durationMillis: 10,
    turnCount: total ? 1 : 0, trackedTurnCount: total ? 1 : 0, modelCallCount: total ? 1 : 0, reportedCallCount: total ? 1 : 0, status: 'COMPLETE' },
  breakdown: { systemPromptTokens: 0, historyTokens: 0, currentUserTokens: 0, toolSchemaTokens: 0, toolResultTokens: 0,
    profileContextTokens: 0, memoryRecallTokens: 0, ragContextTokens: 0, otherTokens: 0, estimatedInputTokens: 0 },
  byTool: [], byPurpose: [], byModel: [], daily: [],
});
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ TokenUsageSettingsPage } = await server.ssrLoadModule('/src/components/settings/TokenUsageSettingsPage.tsx'));
  ({ SettingsMainRegion } = await server.ssrLoadModule('/src/components/settings/SettingsMainRegion.tsx'));
});
after(async () => { await server?.close(); dom?.window.close(); globalThis.fetch = originalFetch; });
beforeEach(() => {
  respond = () => response(overview());
  globalThis.fetch = async url => respond(new URL(String(url)));
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
});
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });
const render = () => React.act(async () => root.render(React.createElement(React.StrictMode, null,
  React.createElement(SettingsMainRegion, null, React.createElement(TokenUsageSettingsPage)))));
const summary = () => container.querySelector('[aria-labelledby="usage-summary-title"]');
const loading = () => container.querySelector('[role="status"]');
async function click(name) {
  const button = [...container.querySelectorAll('button')].find(item => item.textContent === name);
  assert.ok(button, `找不到按钮：${name}`);
  await React.act(async () => button.click());
}

test('首次慢速读取显示统一大树动画，不提前展示零值或空数据', async () => {
  const pending = deferred(); respond = () => pending.promise;
  await render();
  assert.ok(loading().querySelector('svg'));
  const region = container.querySelector('[aria-label="设置主区域"]');
  assert.equal(loading().parentElement.parentElement, region, '遮罩直接属于右侧主区域，不局限于页面版心');
  assert.ok(region.querySelector('h1').closest('[inert]'), '标题及操作一起被遮挡');
  assert.equal(loading().closest('[inert]'), null, '加载状态自身不能被 inert 隐藏');
  assert.match(loading().textContent, /正在汇总所选范围的 Token 用量/);
  assert.equal(summary(), null);
  assert.doesNotMatch(container.textContent, /等待首次调用|暂无调用记录/);
  await React.act(async () => pending.resolve(response(overview())));
  assert.equal(loading(), null);
  assert.equal(summary().querySelector('strong').textContent, '123');
});

test('刷新时整片右侧主区域显示大树遮罩并保留统计节点；失败后结束动画、标记旧数据', async () => {
  await render();
  const previous = summary(), pending = deferred(); respond = () => pending.promise;
  await click('刷新统计');
  assert.ok(loading().querySelector('svg'));
  assert.match(loading().textContent, /正在汇总所选范围的 Token 用量/);
  assert.ok(summary().closest('[inert]'));
  assert.equal(summary(), previous);
  assert.equal(summary().closest('[aria-busy]').getAttribute('aria-busy'), 'true');
  assert.equal([...container.querySelectorAll('button')].find(item => item.textContent === '正在刷新').disabled, true);
  await React.act(async () => pending.reject(new Error('网络暂时不可用')));
  assert.equal(loading(), null);
  assert.equal(summary(), previous);
  assert.equal(summary().closest('[aria-busy]').getAttribute('aria-busy'), 'false');
  assert.match(container.querySelector('[role="alert"]').textContent, /网络暂时不可用.*上次成功加载的数据/);
});

test('首次失败可就地重试，真实零用量只在成功后显示', async () => {
  respond = () => { throw new Error('服务器暂时不可用'); };
  await render();
  assert.equal(loading(), null);
  assert.equal(summary(), null);
  assert.match(container.querySelector('[role="alert"]').textContent, /服务器暂时不可用/);
  const retry = deferred(); respond = () => retry.promise;
  await click('重新加载');
  assert.ok(loading().querySelector('svg'));
  assert.equal(container.querySelector('[role="alert"]'), null);
  await React.act(async () => retry.resolve(response(overview(0))));
  assert.equal(loading(), null);
  assert.equal(summary().querySelector('strong').textContent, '0');
  assert.match(summary().textContent, /等待首次调用/);
});

test('快速切换统计范围展示大树而非旧统计，迟到响应不能覆盖最后选择', async () => {
  await render();
  const week = deferred(), all = deferred();
  respond = url => url.searchParams.has('from') ? week.promise : all.promise;
  await click('近 7 天');
  assert.ok(loading().querySelector('svg'));
  assert.equal(summary(), null);
  await click('全部时间');
  await React.act(async () => all.resolve(response(overview(456))));
  assert.equal(loading(), null);
  assert.equal(summary().querySelector('strong').textContent, '456');
  await React.act(async () => week.resolve(response(overview(789))));
  assert.equal(summary().querySelector('strong').textContent, '456');
});
