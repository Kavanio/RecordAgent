import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount, useFetchStub as createFetchStub, jsonResponse, act, deferred } from '../../testing/harness.mjs';
let React, ChannelView, Models, Message, useSelection, host, stub;
const session = id => ({ id, title: `飞书 ${id}`, kind: 'GENERAL', updatedAt: '2026-11-03T01:00:00Z' });
const detail = (id, content) => ({ summary: session(id), messages: [{ id, role: 'USER', content, status: 'COMPLETED', createdAt: '2026-11-03T01:00:00Z' }] });
before(async () => {
  ({ react: React } = await startHarness());
  HTMLElement.prototype.scrollTo = () => {};
  ({ ChannelConversationView: ChannelView } = await loadModule('/src/components/chat/ChannelConversationView.tsx'));
  ({ useConversationSelection: useSelection } = await loadModule('/src/components/chat/useConversationSelection.ts'));
  ({ ModelProviderContext: Models } = await loadModule('/src/context/ModelContext.tsx'));
  ({ MessageProvider: Message } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);
afterEach(async () => { await host?.unmount(); stub?.restore(); });
function tree(selected) {
  return React.createElement(Message, null, React.createElement(Models, null,
    React.createElement(ChannelView, { session: selected }, React.createElement('textarea', { defaultValue: '客户端未发送草稿', 'aria-label': '客户端输入框' }))));
}
function respond(handler) {
  const requests = [];
  stub = createFetchStub(async (url, init) => {
    requests.push([url.pathname, init?.method || 'GET']);
    return url.pathname.includes('/sessions/feishu/') ? handler(url) : jsonResponse({ providers: [] });
  });
  return requests;
}
test('uses the main chat workspace, preserves desktop draft and provides no sending controls', async () => {
  const calls = respond(() => jsonResponse(detail('a', '飞书消息正文')));
  host = await mount(tree(null));
  const draft = host.query('textarea');
  await host.render(tree(session('a')));
  assert.equal(draft.parentElement.hidden, true);
  assert.match(host.text(), /飞书消息正文/);
  assert.match(host.text(), /飞书对话 · 只读历史/);
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.equal(host.query('[aria-label="发送消息"]'), null);
  assert.equal(host.query('[aria-label="请求批准"]'), null);
  assert.ok(host.query('[data-msg-id="a"]'), '复用主聊天消息行');
  await host.click('刷新历史');
  await host.render(tree(null));
  assert.equal(host.query('textarea'), draft, '客户端输入框没有卸载');
  assert.equal(draft.parentElement.hidden, false);
  assert.equal(draft.value, '客户端未发送草稿');
  assert.ok(calls.every(([, method]) => method === 'GET'));
});
test('switching feishu sessions drops late history and hides the previous selected title', async () => {
  const late = deferred();
  respond(url => url.pathname.endsWith('/a') ? late.promise : jsonResponse(detail('b', '第二个飞书正文')));
  host = await mount(tree(session('a')));
  await host.render(tree(session('b')));
  await act(async () => late.resolve(jsonResponse(detail('a', '迟到的正文'))));
  assert.match(host.text(), /第二个飞书正文/);
  assert.doesNotMatch(host.text(), /迟到的正文/);
  assert.doesNotMatch(host.text(), /飞书 a/);
});
test('failed history shows retry in main area and does not expose a composer', async () => {
  respond(() => ({ ok: false, status: 404 }));
  host = await mount(tree(session('a')));
  assert.match(host.query('[role="alert"]').textContent, /后端已更新并重启/);
  assert.equal(host.query('[aria-label="发送消息"]'), null);
});
test('selection keeps desktop runtime identity separate and clears feishu on desktop selection', async () => {
  function Selection() {
    const value = useSelection();
    return React.createElement('div', null,
      React.createElement('output', null, `${value.activeSessionId}:${value.feishuSession?.id || ''}`),
      React.createElement('button', { onClick: () => value.setActiveSessionId('desktop') }, '桌面'),
      React.createElement('button', { onClick: () => value.selectFeishuSession(session('a')) }, '飞书'));
  }
  host = await mount(React.createElement(Selection));
  await host.click('桌面'); await host.click('飞书');
  assert.equal(host.query('output').textContent, 'desktop:a');
  await host.click('桌面');
  assert.equal(host.query('output').textContent, 'desktop:');
});
