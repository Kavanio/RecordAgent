import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, server, React, createRoot, CalendarView, MessageProvider, StudyRealtimeContext, root, container, respond;
const originalFetch = globalThis.fetch;
const response = data => ({ ok: true, json: async () => ({ code: 200, data }) });
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'Element', 'SVGElement', 'Event', 'MouseEvent', 'FormData', 'localStorage']) globalThis[key] = dom.window[key];
  globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
  globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window);
  globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window);
  globalThis.ResizeObserver = class { observe() {} disconnect() {} };
  window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} });
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ CalendarView } = await server.ssrLoadModule('/src/components/calendar/CalendarView.tsx'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
  ({ StudyRealtimeContext } = await server.ssrLoadModule('/src/context/studyRealtimeState.ts'));
});
after(async () => { await server?.close(); dom?.window.close(); globalThis.fetch = originalFetch; });
beforeEach(() => {
  respond = (url, init) => {
    if (/\/daily-events\/days\/\d{4}-\d{2}-\d{2}$/.test(url.pathname)) return response({ date: url.pathname.split('/').at(-1), events: [] });
    if (url.pathname.endsWith('/statistics')) return response({ totalDurationSeconds: 0, days: [] });
    if (url.pathname.endsWith('/cashflow-summary')) return response({ income: 0, expense: 0 });
    if (init?.method === 'POST') return response({});
    return response([]);
  };
  globalThis.fetch = async (url, init) => respond(new URL(String(url)), init);
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
});
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });
const render = () => React.act(async () => root.render(React.createElement(React.StrictMode, null,
  React.createElement(StudyRealtimeContext.Provider, { value: { syncGeneration: 0, ready: true, activeSession: null } },
    React.createElement(MessageProvider, null, React.createElement(CalendarView))))));
const click = async name => {
  const button = [...document.querySelectorAll('button')].find(item => (item.getAttribute('aria-label') || item.textContent) === name);
  assert.ok(button, `找不到按钮：${name}`);
  await React.act(async () => button.click());
};
const dayPanel = () => container.querySelector('section[aria-label$="的日记录"]');

test('月格和日详情慢速读取时显示骨架，不提前显示零金额、空记录或摘要失败', async () => {
  const normal = respond, daily = deferred(), month = deferred();
  respond = (url, init) => {
    if (/\/daily-events\/days\/\d/.test(url.pathname)) return daily.promise;
    if (url.pathname.endsWith('/daily-events/days')) return month.promise;
    return normal(url, init);
  };
  await render();
  assert.ok(dayPanel().querySelector('[role="status"][aria-label="正在读取当天汇总…"]'));
  assert.ok(container.querySelector('[aria-label="正在读取月历摘要"][aria-busy="true"]'));
  assert.doesNotMatch(dayPanel().textContent, /¥0\.00|暂无待办、日程或收支记录/);
  assert.doesNotMatch(container.textContent, /部分摘要暂不可用/);
  const selected = container.querySelector('button[aria-current="date"]').getAttribute('aria-label').slice(0, 10);
  await React.act(async () => { daily.resolve(response({ date: selected, events: [] })); month.resolve(response([])); });
  assert.equal(dayPanel().getAttribute('aria-busy'), 'false');
  assert.match(dayPanel().textContent, /暂无待办、日程或收支记录/);
});

test('日/月请求失败结束骨架并显示就地重试，成功后恢复真实空态', async () => {
  const normal = respond;
  respond = (url, init) => {
    if (url.pathname.includes('/daily-events/days')) throw new Error('网络暂时不可用');
    return normal(url, init);
  };
  await render();
  assert.equal(dayPanel().getAttribute('aria-busy'), 'false');
  assert.match(dayPanel().textContent, /网络暂时不可用/);
  assert.doesNotMatch(dayPanel().textContent, /¥0\.00|暂无待办、日程或收支记录/);
  assert.equal(container.querySelector('[aria-label="正在读取月历摘要"]'), null);
  respond = normal;
  await click('重新读取日记录');
  assert.match(dayPanel().textContent, /暂无待办、日程或收支记录/);
  assert.equal(dayPanel().querySelector('[role="alert"]'), null);
});

test('记账入口慢速读取账户时显示工作区局部毛玻璃，失败后恢复交互', async () => {
  await render();
  const normal = respond, accounts = deferred();
  respond = (url, init) => {
    if (url.pathname.endsWith('/finance/overview')) return accounts.promise;
    if (url.pathname.endsWith('/finance/categories')) return response({ income: [], expense: [] });
    return normal(url, init);
  };
  await click('新建记录');
  const option = [...document.querySelectorAll('[role="dialog"] button')].find(item => item.querySelector('strong')?.textContent === '记一笔');
  assert.ok(option);
  await React.act(async () => option.click());
  const workspace = container.querySelector('main');
  assert.equal(workspace.getAttribute('aria-busy'), 'true');
  assert.ok(workspace.querySelector('[inert]'));
  assert.match(workspace.querySelector('[role="status"]').textContent, /正在读取财务账户与分类/);
  await React.act(async () => accounts.reject(new Error('读取账户超时')));
  assert.equal(workspace.getAttribute('aria-busy'), 'false');
  assert.equal(workspace.querySelector('[inert]'), null);
  assert.match(document.body.textContent, /读取账户超时/);
  assert.equal(document.querySelector('[role="dialog"]'), null);
});

test('新建记录写入失败保留表单及草稿并移除遮罩，不误当作保存成功关闭', async () => {
  await render();
  await click('新建记录');
  const option = [...document.querySelectorAll('button')].find(item => item.textContent.includes('待办') && item.closest('[role="dialog"]'));
  assert.ok(option);
  await React.act(async () => option.click());
  const normal = respond, write = deferred();
  respond = (url, init) => init?.method === 'POST' ? write.promise : normal(url, init);
  const form = document.querySelector('form');
  form.querySelector('[name="title"]').value = '等待保存的待办';
  await React.act(async () => form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true })));
  const dialog = document.querySelector('[role="dialog"]');
  assert.equal(dialog.getAttribute('aria-busy'), 'true');
  assert.match(dialog.querySelector('[role="status"]').textContent, /正在保存日历记录/);
  await React.act(async () => write.reject(new Error('保存连接中断')));
  assert.equal(document.querySelector('[role="dialog"]'), dialog);
  assert.equal(dialog.getAttribute('aria-busy'), 'false');
  assert.equal(form.querySelector('[name="title"]').value, '等待保存的待办');
  assert.match(dialog.textContent, /保存连接中断/);
});
