import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount, useFetchStub, jsonResponse, act, deferred } from '../../testing/harness.mjs';
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

test('shows an independent group and read-only history, with collapse and search', async () => {
  const calls = [];
  stub = useFetchStub(async (url, init) => {
    calls.push([url.pathname, init?.method ?? 'GET']);
    return jsonResponse(url.pathname.endsWith('/feishu') ? [first, second] : detail(first, '飞书原始正文'));
  });
  view = await mount(React.createElement(Section));
  assert.match(view.text(), /飞书对话/);
  assert.match(view.text(), /飞书讨论一/);
  await view.click('飞书讨论一');
  assert.match(document.querySelector('[role="dialog"]').textContent, /飞书原始正文/);
  assert.match(document.querySelector('[role="dialog"]').textContent, /只读历史/);
  assert.equal(document.querySelector('[role="dialog"] textarea'), null);
  await act(async () => document.querySelector('[aria-label="关闭抽屉"]').click());
  await view.render(React.createElement(Section, { query: '讨论二' }));
  assert.doesNotMatch(view.text(), /飞书讨论一/);
  assert.match(view.text(), /飞书讨论二/);
  const toggle = view.query('[aria-expanded]');
  await act(async () => toggle.click());
  assert.equal(toggle.getAttribute('aria-expanded'), 'false');
  assert.doesNotMatch(view.text(), /飞书讨论二/);
  assert.ok(calls.every(([, method]) => method === 'GET'));
});

test('discloses old-backend failure and retries instead of pretending history is empty', async () => {
  stub = useFetchStub(async () => ({ ok: false, status: 404 }));
  view = await mount(React.createElement(Section));
  assert.match(view.query('[role="alert"]').textContent, /后端已更新并重启/);
  assert.doesNotMatch(view.text(), /暂无飞书对话/);
  stub.respond(async () => jsonResponse([]));
  await view.click('重试');
  assert.match(view.text(), /暂无飞书对话/);
});

test('late response from previous selection cannot overwrite the next conversation', async () => {
  const pending = deferred();
  stub = useFetchStub(async url => {
    if (url.pathname.endsWith('/feishu')) return jsonResponse([first, second]);
    if (url.pathname.endsWith('/feishu-a')) return pending.promise;
    return jsonResponse(detail(second, '第二段历史'));
  });
  view = await mount(React.createElement(Section));
  await view.click('飞书讨论一');
  await view.click('飞书讨论二');
  await act(async () => pending.resolve(jsonResponse(detail(first, '迟到的第一段历史'))));
  const dialog = document.querySelector('[role="dialog"]');
  assert.match(dialog.textContent, /第二段历史/);
  assert.doesNotMatch(dialog.textContent, /迟到的第一段历史/);
});
