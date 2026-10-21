import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, server, React, createRoot, SettingsMainRegion, SettingsPageLayout, ProfileSettingsPage, TokenUsageSettingsPage, MessageProvider;
let root, container, respond;
const originalFetch = globalThis.fetch;
const response = data => ({ ok: true, json: async () => ({ code: 200, data }) });
const overview = {
  from: null, to: null, sessionId: null,
  totals: { inputTokens: 123, outputTokens: 0, cachedInputTokens: 0, totalTokens: 123, durationMillis: 0,
    turnCount: 1, trackedTurnCount: 1, modelCallCount: 1, reportedCallCount: 1, status: 'COMPLETE' },
  breakdown: { systemPromptTokens: 0, historyTokens: 0, currentUserTokens: 0, toolSchemaTokens: 0, toolResultTokens: 0,
    profileContextTokens: 0, memoryRecallTokens: 0, ragContextTokens: 0, otherTokens: 0, estimatedInputTokens: 0 },
  byTool: [], byPurpose: [], byModel: [], daily: [],
};
const account = { bound: true, maskedEmail: 'u***@example.com', avatarVersion: null, emailNotificationsEnabled: false };
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement', 'FormData']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ SettingsMainRegion } = await server.ssrLoadModule('/src/components/settings/SettingsMainRegion.tsx'));
  ({ SettingsPageLayout } = await server.ssrLoadModule('/src/components/settings/SettingsPageLayout.tsx'));
  ({ ProfileSettingsPage } = await server.ssrLoadModule('/src/components/settings/ProfileSettingsPage.tsx'));
  ({ TokenUsageSettingsPage } = await server.ssrLoadModule('/src/components/settings/TokenUsageSettingsPage.tsx'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
});
after(async () => { await server?.close(); dom?.window.close(); globalThis.fetch = originalFetch; });
beforeEach(() => {
  respond = () => response(overview);
  globalThis.fetch = async (url, init) => respond(new URL(String(url)), init);
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
});
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });

// 与设置壳层相同的边界：侧栏和固定可视区域同级，滚动正文位于主区域内。
function Shell({ accountLoading = false }) {
  const [tab, setTab] = React.useState('profile');
  return React.createElement(MessageProvider, null,
    React.createElement('aside', { 'aria-label': '设置导航' },
      ...['profile', 'usage', 'static'].map(value => React.createElement('button', { key: value, onClick: () => setTab(value) }, value))),
    React.createElement(SettingsMainRegion, { contentClassName: 'settings-scroll-content' },
      tab === 'profile' ? React.createElement(ProfileSettingsPage, { accountStatus: account, accountLoading })
        : tab === 'usage' ? React.createElement(TokenUsageSettingsPage)
          : React.createElement(SettingsPageLayout, { title: '本机设置', description: '无需请求' }, React.createElement('button', null, '可操作项'))));
}
const render = props => React.act(async () => root.render(React.createElement(React.StrictMode, null, React.createElement(Shell, props))));
const region = () => container.querySelector('[aria-label="设置主区域"]');
const loading = () => region().querySelector('[role="status"]');
async function click(name) {
  const button = [...container.querySelectorAll('button')].find(item => item.textContent === name);
  assert.ok(button, `找不到按钮：${name}`);
  await React.act(async () => button.click());
}

test('个人资料首次读取遮罩覆盖右侧标题/正文，侧栏可用；完成后解除交互限制', async () => {
  const pending = deferred(); respond = () => pending.promise;
  await render();
  assert.match(loading().textContent, /正在读取个人资料与用量/);
  assert.ok(loading().querySelector('svg'));
  assert.equal(loading().parentElement.parentElement, region());
  assert.ok(region().querySelector('h1').closest('[inert]'));
  assert.ok(region().querySelector('button[title="点击更换头像"]').closest('[inert]'));
  assert.equal(container.querySelector('aside').closest('[inert]'), null);
  assert.equal(container.querySelector('aside').contains(loading()), false);
  await React.act(async () => pending.resolve(response(overview)));
  assert.equal(loading(), null);
  assert.equal(region().querySelector('[inert]'), null);
  assert.match(region().textContent, /累计 Token 数123/);
});

test('账户状态尚未就绪时继续等待，不能仅因用量返回就提前解除遮罩', async () => {
  await render({ accountLoading: true });
  assert.ok(loading());
  await render({ accountLoading: false });
  assert.equal(loading(), null);
});

test('刷新保留资料节点，失败退出等待并保留上次数据；首次失败也可重试', async () => {
  await render();
  const previous = region().querySelector('[aria-label="近一年每日 Token 用量热力图"]');
  const pending = deferred(); respond = () => pending.promise;
  await click('刷新统计');
  assert.ok(loading());
  assert.equal(region().querySelector('[aria-label="近一年每日 Token 用量热力图"]'), previous);
  await React.act(async () => pending.reject(new Error('网络中断')));
  assert.equal(loading(), null);
  assert.equal(region().querySelector('[inert]'), null);
  assert.match(region().textContent, /刷新失败，当前显示上次成功加载的数据/);
  await click('static');
  await click('profile');
  assert.equal(loading(), null);
  assert.match(region().querySelector('[role="alert"]').textContent, /网络中断/);
  respond = () => response(overview);
  await click('重新加载');
  assert.equal(region().querySelector('[role="alert"]'), null);
});

test('等待中可从侧栏切换，旧页迟到响应不能移除新页遮罩或让静态页再次变忙', async () => {
  const profile = deferred(), usage = deferred();
  respond = url => url.searchParams.has('from') ? usage.promise : profile.promise;
  await render();
  await click('usage');
  assert.match(loading().textContent, /正在汇总所选范围的 Token 用量/);
  await React.act(async () => profile.resolve(response(overview)));
  assert.ok(loading(), '旧个人资料响应不能解除 Token 页遮罩');
  await click('static');
  assert.equal(loading(), null);
  assert.equal(region().querySelector('[inert]'), null);
  await React.act(async () => usage.resolve(response(overview)));
  assert.equal(loading(), null);
  assert.match(region().textContent, /本机设置/);
});

test('头像上传也复用右侧毛玻璃；失败释放遮罩并提示，不覆盖侧栏', async () => {
  await render();
  const pending = deferred();
  respond = (url, init) => init?.method === 'POST' && url.pathname.endsWith('/avatar') ? pending.promise : response(overview);
  const input = region().querySelector('input[type="file"]');
  Object.defineProperty(input, 'files', { configurable: true, value: [new window.File(['png'], 'avatar.png', { type: 'image/png' })] });
  await React.act(async () => input.dispatchEvent(new window.Event('change', { bubbles: true })));
  assert.match(loading().textContent, /正在上传头像/);
  assert.equal(container.querySelector('aside').closest('[inert]'), null);
  await React.act(async () => pending.reject(new Error('上传连接中断')));
  assert.equal(loading(), null);
  assert.equal(region().querySelector('[inert]'), null);
  assert.match(document.body.textContent, /上传连接中断/);
});

test('布局独立使用时有局部降级遮罩，不要求每个调用方都有设置壳层', async () => {
  await React.act(async () => root.render(React.createElement(SettingsPageLayout, { title: '独立设置', description: '', loading: true }, '正文')));
  assert.ok(container.querySelector('[role="status"] svg'));
  assert.ok(container.querySelector('h1').closest('[inert]'));
  assert.equal(container.querySelector('[role="status"]').closest('[inert]'), null);
});
