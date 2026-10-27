import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount, act, getReact, useFetchStub, jsonResponse } from '../../testing/harness.mjs';
let React, Workspace, Models, Message, host, stub, sent, supportsVision;
before(async () => {
  await startHarness({ globals: ['FileReader'] }); React = getReact();
  HTMLElement.prototype.scrollTo = () => {};
  ({ ChatWorkspace: Workspace } = await loadModule('/src/components/chat/ChatWorkspace.tsx'));
  ({ ModelProviderContext: Models } = await loadModule('/src/context/ModelContext.tsx'));
  ({ MessageProvider: Message } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);
beforeEach(() => {
  sent = []; supportsVision = true;
  stub = useFetchStub((url) => {
    if (url.pathname.endsWith('/full-config')) return jsonResponse({
      activeVendor: 'openai', activeModel: 'v', providers: [{ id: 'openai', type: 'openai', models: [
        { id: 'v', name: 'vision', supportsVision },
      ] }],
    });
    return jsonResponse({ enabled: false, available: false, events: [], categories: [], items: [] });
  });
});
afterEach(async () => { await host?.unmount(); stub.restore(); });

function tree(sessionId = 'one') {
  return React.createElement(Message, null, React.createElement(Models, null, React.createElement(Workspace, {
    sessionId, messages: [{ id: 'previous', role: 'user', content: 'hello' }],
    onOpenFeature() {}, onOpenRecords() {}, onSendMessage(...args) { sent.push(args); }, onOpenSettings() {},
    onRenameSession: async () => ({ success: true }), subagentTasks: [], isSubagentTasksLoading: false,
    subagentTaskError: null, cancellingTaskId: null, onRefreshSubagentTasks() {}, onCancelSubagentTask() {},
    permissionMode: 'ASK', onPermissionModeChange() {},
  })));
}
async function attach() {
  const input = host.query('input[type="file"]');
  await act(async () => {
    Object.defineProperty(input, 'files', { value: [new File(['abcd'], 'test.png', { type: 'image/png' })], configurable: true });
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await new Promise(resolve => setTimeout(resolve, 25));
  });
}

test('真实聊天编排允许纯图片发送并消费草稿', async () => {
  host = await mount(tree()); await attach();
  assert.equal(host.query('[aria-label="发送消息"]').disabled, false);
  await act(async () => host.query('[aria-label="发送消息"]').click());
  assert.equal(sent.length, 1); assert.equal(sent[0][4].length, 1);
  assert.equal(host.query('[aria-label="已附加图片"]'), null);
});

test('文本模型不发送图片，展示中文原因且保留草稿', async () => {
  supportsVision = false; host = await mount(tree()); await attach();
  await act(async () => host.query('[aria-label="发送消息"]').click());
  assert.equal(sent.length, 0);
  assert.match(document.body.textContent, /当前模型未标注图片识别能力/);
  assert.ok(host.query('[aria-label="已附加图片"] img'));
});

test('切换会话后图片不会跟着新会话发出去', async () => {
  host = await mount(tree()); await attach();
  await host.render(tree('two'));
  assert.equal(host.query('[aria-label="已附加图片"]'), null);
  assert.equal(host.query('[aria-label="发送消息"]').disabled, true);
});
