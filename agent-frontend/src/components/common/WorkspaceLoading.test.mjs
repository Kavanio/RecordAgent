import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, server, React, createRoot, RecordPage, StudyPage, StudyRealtimeContext, MessageProvider, root, container, respond;
const originalFetch = globalThis.fetch;
const response = data => ({ ok: true, json: async () => ({ code: 200, data }) });
const dateKey = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
const today = dateKey(new Date());
const record = title => ({ id: title, recordDate: today, type: 'quick', title, contentHtml: '<p>正文</p>', contentText: '正文', tags: ['测试'], tabId: 'quick', pinned: false, favorite: false, version: 1 });
const session = { id: 'study', content: '真实的学习记录', category: '阅读', startedAt: `${today}T09:00:00`, endedAt: `${today}T10:00:00`, durationSeconds: 3600, status: 'completed', source: 'manual', version: 1 };
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'SVGElement', 'FormData', 'localStorage']) globalThis[key] = dom.window[key];
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: dom.window.navigator });
  globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
  globalThis.requestAnimationFrame = dom.window.requestAnimationFrame.bind(dom.window);
  globalThis.cancelAnimationFrame = dom.window.cancelAnimationFrame.bind(dom.window);
  globalThis.ResizeObserver = class { observe() {} disconnect() {} };
  window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} });
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ RecordPage } = await server.ssrLoadModule('/src/components/record/RecordPage.tsx'));
  ({ StudyPage } = await server.ssrLoadModule('/src/components/study/StudyPage.tsx'));
  ({ StudyRealtimeContext } = await server.ssrLoadModule('/src/context/studyRealtimeState.ts'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
});
after(async () => { await server?.close(); dom?.window.close(); globalThis.fetch = originalFetch; });
beforeEach(() => {
  respond = url => {
    if (url.pathname.endsWith('/records/tabs')) return response([{ id: 'quick', name: '随记', systemKey: 'quick', sortOrder: 0 }]);
    if (url.pathname.endsWith('/records')) return response([record('真实的资料标题')]);
    if (url.pathname.endsWith('/statistics')) return response({ from: url.searchParams.get('from'), to: url.searchParams.get('to'), totalDurationSeconds: 3600, studyDays: 1, sessionCount: 1, averageDailySeconds: 3600, days: [{ date: today, durationSeconds: 3600, sessionCount: 1 }] });
    if (url.pathname.endsWith('/categories')) return response(['阅读']);
    if (url.pathname.endsWith('/study-sessions')) return response([session]);
    throw new Error(`未预期的请求：${url}`);
  };
  globalThis.fetch = async (url, init) => respond(new URL(String(url)), init);
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
});
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });
const wait = ms => new Promise(resolve => window.setTimeout(resolve, ms));
async function render(page = 'record', generation = 0) {
  await React.act(async () => {
    root.render(React.createElement(React.StrictMode, null, React.createElement(MessageProvider, null,
      React.createElement(StudyRealtimeContext.Provider, { value: { syncGeneration: generation, ready: true, updatedAt: 0, activeSession: null } },
        React.createElement(page === 'record' ? RecordPage : StudyPage)))));
  });
  await React.act(async () => wait(10));
}
async function click(name) {
  const button = [...container.querySelectorAll('button')].find(item => (item.getAttribute('aria-label') || item.textContent) === name);
  assert.ok(button, `找不到按钮：${name}`);
  await React.act(async () => button.click());
}
async function search(value) {
  const input = container.querySelector('input[aria-label="搜索资料"]');
  await React.act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(input, value);
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
  });
  await React.act(async () => wait(270));
}

test('资料首次慢速读取使用文章行骨架，不提前显示零篇或首次空态', async () => {
  const normal = respond, pending = deferred();
  respond = url => url.pathname.endsWith('/records') ? pending.promise : normal(url);
  await render();
  assert.ok(container.querySelector('[aria-label="正在整理资料列表…"][role="status"]'));
  assert.doesNotMatch(container.textContent, /0 篇|从第一篇记录开始|本周尚未复盘/);
  await React.act(async () => pending.resolve(response([record('真实的资料标题')])));
  assert.equal(container.querySelector('[aria-label="正在整理资料列表…"]'), null);
  assert.match(container.textContent, /真实的资料标题/);
});

test('资料读取失败结束骨架并就地重试，不伪造空库', async () => {
  const normal = respond;
  respond = url => { if (url.pathname.endsWith('/records')) throw new Error('资料服务暂时不可用'); return normal(url); };
  await render();
  assert.match(container.querySelector('[role="alert"]').textContent, /资料服务暂时不可用/);
  assert.equal(container.querySelector('[aria-label="正在整理资料列表…"]'), null);
  assert.doesNotMatch(container.textContent, /从第一篇记录开始/);
  respond = normal;
  await click('重新加载资料');
  assert.match(container.textContent, /真实的资料标题/);
});

test('搜索使用文章骨架，迟到的旧搜索不能覆盖新结果；同查询刷新保留行节点', async () => {
  await render();
  const normal = respond, first = deferred(), second = deferred();
  respond = url => {
    if (!url.pathname.endsWith('/records')) return normal(url);
    return url.searchParams.get('query') === 'first' ? first.promise : second.promise;
  };
  await search('first');
  assert.ok(container.querySelector('[aria-label="正在整理资料列表…"]'));
  await search('second');
  await React.act(async () => second.resolve(response([record('新的搜索结果')])));
  await React.act(async () => first.resolve(response([record('过期搜索结果')])));
  assert.match(container.textContent, /新的搜索结果/);
  assert.doesNotMatch(container.textContent, /过期搜索结果/);
  const row = container.querySelector('article');
  const pending = deferred();
  respond = (url, init) => init?.method === 'PATCH' ? response({}) : url.pathname.endsWith('/records') ? pending.promise : normal(url);
  await click('收藏');
  assert.equal(container.querySelector('article'), row);
  assert.match(container.textContent, /正在更新资料/);
  await React.act(async () => pending.resolve(response([record('新的搜索结果')])));
});

test('记录首次读取提供概览、轨迹、近期记录及热力图骨架，侧栏统计不伪造零值', async () => {
  const pending = deferred(); respond = () => pending.promise;
  await render('study');
  for (const label of ['正在汇总学习记录…', '正在读取这一天的轨迹…', '正在读取最近的学习记录…', '正在生成学习热力图…', '正在读取学习节奏…']) {
    assert.ok(container.querySelector(`[aria-label="${label}"]`), label);
  }
  assert.equal(container.querySelector('[aria-label="正在生成学习热力图…"]').querySelectorAll('rect').length, 371);
  assert.doesNotMatch(container.textContent, /还没有学习记录|0 \/ 7 天|0 次/);
  // 卸载时即使请求仍挂起，也必须清理界面订阅；不访问真实学习服务。
});

test('SSE 同日刷新保留时间轨迹和近期记录，切换日期才展示新轨迹骨架', async () => {
  await render('study');
  const timeline = container.querySelector('[aria-label="全天学习轨迹，可横向滚动查看"]');
  assert.ok(timeline);
  const recent = [...container.querySelectorAll('button')].find(item => item.textContent.includes('真实的学习记录'));
  assert.ok(recent);
  const normal = respond, pending = deferred();
  respond = url => url.pathname.endsWith('/study-sessions') ? pending.promise : normal(url);
  await render('study', 1);
  assert.equal(container.querySelector('[aria-label="全天学习轨迹，可横向滚动查看"]'), timeline);
  assert.equal(container.querySelector('[aria-label="正在读取最近的学习记录…"]'), null);
  assert.equal([...container.querySelectorAll('button')].find(item => item.textContent.includes('真实的学习记录')), recent);
  await React.act(async () => pending.resolve(response([session])));
  assert.equal(container.querySelector('[aria-label="全天学习轨迹，可横向滚动查看"]'), timeline);
  const previous = deferred();
  respond = url => url.pathname.endsWith('/study-sessions') ? previous.promise : normal(url);
  await click('前一天');
  assert.ok(container.querySelector('[aria-label="正在读取这一天的轨迹…"]'));
  await React.act(async () => previous.resolve(response([])));
  assert.match(container.textContent, /这一天还没有学习记录/);
});

test('记录统计失败不会让热力图永久等待，重试后恢复真实内容', async () => {
  const normal = respond;
  respond = url => { if (url.pathname.endsWith('/statistics')) throw new Error('统计读取失败'); return normal(url); };
  await render('study');
  assert.match(container.querySelector('[role="alert"]').textContent, /统计读取失败/);
  assert.equal(container.querySelector('[aria-label="正在生成学习热力图…"]'), null);
  assert.doesNotMatch(container.textContent, /还没有学习记录|0 \/ 7 天/);
  respond = normal;
  await click('重新加载记录');
  assert.equal(container.querySelector('[role="alert"]'), null);
  assert.match(container.textContent, /真实的学习记录/);
});
