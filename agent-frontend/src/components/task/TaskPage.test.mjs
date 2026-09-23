import assert from 'node:assert/strict';
import { test, before, after, beforeEach, afterEach } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

// 真实 React 组件与内存 DOM；HTTP 全部拦截，不访问用户后端。
let server, dom, React, createRoot, TaskPage, StateContext, MessageProvider, newAutomation;
let root, container, snapshot, requests, historyStatus;
const originalFetch = globalThis.fetch;
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'Event', 'MouseEvent']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
  ({ TaskPage } = await server.ssrLoadModule('/src/components/task/TaskPage.tsx'));
  ({ AutomationStateContext: StateContext } = await server.ssrLoadModule('/src/context/automationState.ts'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
  ({ newAutomation } = await server.ssrLoadModule('/src/features/automation/presentation.ts'));
});
after(async () => { await server?.close(); dom?.window.close(); globalThis.fetch = originalFetch; });
beforeEach(async () => {
  requests = []; historyStatus = 'PENDING';
  snapshot = { tasks: ['A', 'B'].map((id, i) => ({ id, version: 1, status: i ? 'PAUSED' : 'ENABLED', nextAt: null, activeSeconds: 0,
    spec: { ...newAutomation(), title: `任务${id}`, content: `内容${id}` } })), pending: [] };
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), ...options });
    let data = {};
    if (String(url).endsWith('/runs')) data = [{ id: 'run', taskId: 'A', title: '任务A', content: '内容A', createdAt: 0,
      status: 'SUCCESS', desktopStatus: 'SUBMITTED', emailStatus: historyStatus, confirmation: 'NONE', error: '' }];
    return { ok: true, json: async () => ({ code: 200, data }) };
  };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await render();
});
afterEach(async () => { await React.act(async () => root?.unmount()); container?.remove(); });
async function render() {
  await React.act(async () => root.render(React.createElement(MessageProvider, null,
    React.createElement(StateContext.Provider, { value: { snapshot, connected: true, activity: null } },
      React.createElement(TaskPage, { onOpenSettings() {} })))));
}
async function click(text, index = 0) {
  const button = [...document.querySelectorAll('button')].filter(b => b.textContent === text)[index];
  assert.ok(button, `找不到按钮 ${text}`);
  await React.act(async () => button.click());
}
async function input(id, value) {
  const element = document.getElementById(id);
  await React.act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(element, value);
    element.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
  });
}
test('暂停筛选展示 PAUSED 任务', async () => {
  await click('已暂停');
  assert.match(container.textContent, /任务B/);
  assert.doesNotMatch(container.textContent, /任务A/);
});
test('从 A 切到 B 后保存 B 的内容和 ID', async () => {
  await click('编辑', 0);
  assert.equal(document.getElementById('task-title').value, '任务A');
  await click('编辑', 1);
  assert.equal(document.getElementById('task-title').value, '任务B');
  await React.act(async () => document.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  const request = requests.find(r => r.method === 'PUT');
  assert.ok(request.url.endsWith('/B'));
  assert.equal(JSON.parse(request.body).spec.content, '内容B');
});
test('未保存修改切换需显式放弃，取消保留草稿', async () => {
  await click('编辑', 0); await input('task-title', '修改后的A'); await click('编辑', 1);
  assert.match(document.body.textContent, /放弃未保存的修改/);
  await click('继续编辑');
  assert.equal(document.getElementById('task-title').value, '修改后的A');
  await click('编辑', 1); await click('放弃并继续');
  assert.equal(document.getElementById('task-title').value, '任务B');
});
test('执行状态 SSE 快照变化刷新已打开的历史', async () => {
  await click('执行历史', 0);
  assert.match(document.body.textContent, /邮件：等待投递/);
  historyStatus = 'SUBMITTED'; snapshot = { ...snapshot }; await render();
  assert.match(document.body.textContent, /邮件：已提交/);
});

test('普通提醒提交真实正文，原生失败不能回报已提交', async () => {
  const { deliverTaskNotification } = await server.ssrLoadModule('/src/services/taskDesktop.ts');
  const originalNotification = window.Notification;
  globalThis.isTauri = true;
  window.Notification = { permission: 'granted' };
  const calls = [];
  window.__TAURI_INTERNALS__ = { invoke: async (command, args) => { calls.push({ command, args }); } };
  try {
    const run = { title: '喝水', content: '起来喝一杯水', sound: false, confirmation: 'NONE' };
    assert.equal(await deliverTaskNotification(run), 'SUBMITTED');
    assert.deepEqual(calls, [{ command: 'send_task_notification', args: { title: '喝水', body: '起来喝一杯水', sound: false } }]);
    window.__TAURI_INTERNALS__.invoke = async () => { throw new Error('系统通知失败'); };
    await assert.rejects(deliverTaskNotification(run), /系统通知失败/);
  } finally {
    globalThis.isTauri = false; delete window.__TAURI_INTERNALS__; window.Notification = originalNotification;
  }
});
