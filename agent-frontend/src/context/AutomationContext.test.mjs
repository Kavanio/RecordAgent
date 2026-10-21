import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

// 真实 Provider 和任务页；只替换网络与原生平台边界，不访问用户后端。
let server, dom, React, createRoot, AutomationProvider, TaskPage, MessageProvider, useAutomations, newAutomation;
let root, container, state, sources, requests, requestHandler;
const originalFetch = globalThis.fetch, originalEventSource = globalThis.EventSource;
before(async () => {
  dom = new JSDOM('<!doctype html><body></body>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'Event', 'MouseEvent']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ AutomationProvider } = await server.ssrLoadModule('/src/context/AutomationContext.tsx'));
  ({ useAutomations } = await server.ssrLoadModule('/src/context/automationState.ts'));
  ({ TaskPage } = await server.ssrLoadModule('/src/components/task/TaskPage.tsx'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
  ({ newAutomation } = await server.ssrLoadModule('/src/features/automation/presentation.ts'));
});
after(async () => { await server.close(); dom.window.close(); globalThis.fetch = originalFetch; globalThis.EventSource = originalEventSource; });
function Observe() { state = useAutomations(); return null; }
const flush = action => React.act(async () => { action?.(); await new Promise(resolve => setTimeout(resolve, 10)); });
const tasks = () => ['A', 'B'].map(id => ({ id, version: 1, status: 'ENABLED', nextAt: null, activeSeconds: 0, spec: { ...newAutomation(), title: `任务${id}` } }));
beforeEach(async () => {
  sources = []; requests = []; requestHandler = () => true;
  globalThis.EventSource = class {
    constructor() { this.listeners = new Map(); this.readyState = 1; sources.push(this); }
    addEventListener(name, receive) { this.listeners.set(name, receive); }
    emit(value) { this.listeners.get('snapshot')?.({ data: JSON.stringify(value) }); }
    close() { this.readyState = 2; }
  };
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), ...options });
    const data = await requestHandler(String(url), options);
    return { ok: true, json: async () => ({ code: 200, data }) };
  };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await flush(() => root.render(React.createElement(MessageProvider, null,
    React.createElement(AutomationProvider, null, React.createElement(Observe), React.createElement(TaskPage, { onOpenSettings() {} })))));
});
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });

test('首次连接未返回快照不冒充空任务列表', async () => {
  assert.doesNotMatch(container.textContent, /让提醒按计划发生/);
  assert.match(container.textContent, /正在读取任务/);
});

test('单条通知领取失败不改变同步状态，也不禁用其他任务', async () => {
  requestHandler = url => { if (url.endsWith('/claim')) throw new Error('领取暂时失败'); return true; };
  await flush(() => sources[0].emit({ tasks: tasks(), pending: [{ id: 'r', taskId: 'A', title: '提醒', content: '内容', desktopStatus: 'PENDING', confirmation: 'NONE' }] }));
  assert.equal(state.connected, true);
  assert.equal(container.querySelector('[aria-label="暂停"]').disabled, false);
  assert.match(container.textContent, /领取暂时失败/);
});

test('仅推送断线时仍可管理任务，先读取权威状态且写入后刷新列表', async () => {
  let current = { tasks: tasks(), pending: [] };
  requestHandler = (url, options) => {
    if (url.endsWith('/A/state')) {
      current = { ...current, tasks: current.tasks.map(task => task.id === 'A' ? { ...task, version: 2, status: 'PAUSED' } : task) };
      return current.tasks[0];
    }
    assert.equal(options.method, 'GET');
    return current;
  };
  await flush(() => sources[0].emit(current));
  await flush(() => sources[0].onerror());
  const pause = container.querySelector('[aria-label="暂停"]');
  assert.equal(pause.disabled, false);
  await flush(() => pause.click());
  assert.equal(requests.filter(request => request.url.endsWith('/A/state')).length, 1);
  assert.equal(requests[0].method, 'GET');
  assert.equal(state.snapshot.tasks[0].status, 'PAUSED');
  assert.equal(state.connected, false, 'REST刷新不能冒充推送恢复');
});

test('任务页明确展示重连状态并提供手动重试入口', async () => {
  await flush(() => sources[0].emit({ tasks: tasks(), pending: [] }));
  assert.match(container.querySelector('[aria-label="任务同步状态"]').textContent, /已同步/);
  await flush(() => sources[0].onerror());
  assert.match(container.querySelector('[aria-label="任务同步状态"]').textContent, /正在重连/);
  const count = sources.length;
  await flush(() => container.querySelector('[aria-label="重试任务同步"]').click());
  assert.equal(sources.length, count + 1);
  assert.equal(sources[0].readyState, 2);
});

test('断线后发现版本变化只刷新不写入，后端不可用时不发送写请求', async () => {
  const initial = { tasks: tasks(), pending: [] };
  await flush(() => sources[0].emit(initial));
  await flush(() => sources[0].onerror());
  requestHandler = () => ({ ...initial, tasks: initial.tasks.map(task => ({ ...task, version: 2 })) });
  await flush(() => container.querySelector('[aria-label="暂停"]').click());
  assert.equal(requests.some(request => request.method === 'POST'), false);
  assert.equal(state.snapshot.tasks[0].version, 2);
  assert.match(container.textContent, /任务状态已变化/);
  requestHandler = () => { throw new Error('后端不可用'); };
  await flush(() => container.querySelector('[aria-label="暂停"]').click());
  assert.equal(requests.some(request => request.method === 'POST'), false);
  assert.match(container.textContent, /后端不可用/);
});

test('纯计时更新不重读执行历史，真实执行变化及重连快照仍刷新历史', async () => {
  requestHandler = url => url.endsWith('/runs') ? [] : true;
  const initial = { tasks: tasks(), pending: [] };
  await flush(() => sources[0].emit(initial));
  await flush(() => container.querySelector('[aria-label="执行历史"]').click());
  const count = () => requests.filter(request => request.url.endsWith('/runs')).length;
  const before = count();
  await flush(() => sources[0].emit({ ...initial, activityOnly: true, tasks: initial.tasks.map(task => ({ ...task, activeSeconds: 5 })) }));
  assert.equal(state.snapshot.tasks[0].activeSeconds, 5);
  assert.equal(count(), before, '仅计时变化不能导致历史请求');
  await flush(() => sources[0].emit(initial));
  assert.equal(count(), before + 1, '完整状态变化必须刷新历史');
});

test('迟到REST快照不覆盖期间收到的新SSE状态', async () => {
  const initial = { tasks: tasks(), pending: [] };
  await flush(() => sources[0].emit(initial));
  let finish, refreshing;
  requestHandler = () => new Promise(resolve => { finish = resolve; });
  await flush(() => { refreshing = state.refreshSnapshot(); });
  await flush(() => sources[0].emit({ ...initial, tasks: initial.tasks.map(task => ({ ...task, version: 3 })) }));
  await React.act(async () => {
    finish({ ...initial, tasks: initial.tasks.map(task => ({ ...task, version: 2 })) });
    await refreshing;
  });
  assert.equal(state.snapshot.tasks[0].version, 3);
});

test('写后REST刷新比在途SSE更新时，不回退到旧任务版本', async () => {
  const initial = { tasks: tasks(), pending: [], sourceId: 'backend-one', revision: 1 };
  await flush(() => sources[0].emit(initial));
  requestHandler = () => ({ ...initial, revision: 3, tasks: initial.tasks.map(task => ({ ...task, version: 3 })) });
  await React.act(async () => { await state.refreshSnapshot(); });
  await flush(() => sources[0].emit({ ...initial, revision: 2, tasks: initial.tasks.map(task => ({ ...task, version: 2 })) }));
  assert.equal(state.snapshot.tasks[0].version, 3);
});

test('后端重启后接受新来源的低序号，拒绝已退役来源的迟到响应', async () => {
  const initial = { tasks: tasks(), pending: [], sourceId: 'old-backend', revision: 50 };
  await flush(() => sources[0].emit(initial));
  await flush(() => sources[0].emit({ ...initial, sourceId: 'new-backend', revision: 1, tasks: initial.tasks.map(task => ({ ...task, version: 4 })) }));
  await flush(() => sources[0].emit({ ...initial, revision: 51, tasks: initial.tasks.map(task => ({ ...task, version: 2 })) }));
  assert.equal(state.snapshot.sourceId, 'new-backend');
  assert.equal(state.snapshot.tasks[0].version, 4);
});

test('操作已成功但列表读取失败时明确区分结果，手动重连不重放写入', async () => {
  await flush(() => sources[0].emit({ tasks: tasks(), pending: [] }));
  requestHandler = (url, options) => {
    if (options.method === 'POST') return { id: 'A' };
    throw new Error('读取暂不可用');
  };
  await flush(() => container.querySelector('[aria-label="暂停"]').click());
  assert.match(container.textContent, /操作已完成，列表暂未刷新/);
  const retry = [...container.querySelectorAll('button')].find(button => button.textContent === '重试连接');
  await flush(() => retry.click());
  assert.equal(requests.filter(request => request.method === 'POST').length, 1);
});
