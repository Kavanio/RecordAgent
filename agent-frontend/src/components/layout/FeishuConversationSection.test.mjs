import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';
let React, Section, view, stub;
const session = (id, title) => ({ id, title, kind: 'GENERAL', status: 'ACTIVE', updatedAt: '2026-11-03T01:00:00Z' });
const first = session('feishu-a', '飞书讨论一');
const second = session('feishu-b', '飞书讨论二');
const detail = (s, content) => ({ summary: s, messages: [{ id: s.id, role: 'USER', content, status: 'COMPLETED', createdAt: s.updatedAt }] });
before(async () => {
  ({ react: React } = await startHarness());
  ({ FeishuConversationSection: Section } = await loadModule('/src/components/layout/FeishuConversationSection.tsx'));
});
after(stopHarness);
afterEach(async () => { await view?.unmount(); stub?.restore(); });

test('collapsed by default, expands on click, selects in main area, and supports search', async () => {
  const calls = [];
  stub = useFetchStub(async (url, init) => {
    calls.push([url.pathname, init?.method ?? 'GET']);
    return jsonResponse(url.pathname.endsWith('/feishu') ? [first, second] : detail(first, '飞书原始正文'));
  });
  const selected = [];
  const props = { activeSessionId: first.id, onSelect: session => selected.push(session.id) };
  view = await mount(React.createElement(Section, props));
  assert.match(view.text(), /飞书对话/);
  const toggle = view.query('[aria-expanded]');
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.doesNotMatch(view.text(), /飞书讨论一/);
  await act(async () => toggle.click());
  assert.equal(toggle.getAttribute('aria-expanded'), 'true');
  assert.match(view.text(), /飞书讨论一/);
  await view.click('飞书讨论一');
  assert.deepEqual(selected, [first.id]);
  assert.equal(view.query('[aria-current="page"]').textContent, first.title);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(calls.length, 1, '分组只加载列表，正文归主区域');
  await view.render(React.createElement(Section, { ...props, query: '讨论二' }));
  assert.doesNotMatch(view.text(), /飞书讨论一/);
  assert.match(view.text(), /飞书讨论二/);
  await act(async () => toggle.click());
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.doesNotMatch(view.text(), /飞书讨论二/);
  assert.ok(calls.every(([, method]) => method === 'GET'));
});

test('expands automatically when mounted with active search query', async () => {
  stub = useFetchStub(async () => jsonResponse([first, second]));
  view = await mount(React.createElement(Section, { query: '讨论二' }));
  assert.equal(view.query('[aria-expanded]').getAttribute('aria-expanded'), 'true');
  assert.doesNotMatch(view.text(), /飞书讨论一/);
  assert.match(view.text(), /飞书讨论二/);
});

test('discloses old-backend failure and retries instead of pretending history is empty', async () => {
  stub = useFetchStub(async () => ({ ok: false, status: 404 }));
  view = await mount(React.createElement(Section));
  await act(async () => view.query('[aria-expanded]').click());
  assert.match(view.query('[role="alert"]').textContent, /后端已更新并重启/);
  assert.doesNotMatch(view.text(), /暂无飞书对话/);
  stub.respond(async () => jsonResponse([]));
  await view.click('重试');
  assert.match(view.text(), /暂无飞书对话/);
});
