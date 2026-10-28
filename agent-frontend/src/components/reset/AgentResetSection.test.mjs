import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';
let view;
before(() => startHarness());
after(() => stopHarness());
afterEach(async () => { await view?.unmount(); view = null; });
test('预览显示实际后端及保留清单，错误确认不会提交删除', async () => {
  let submissions = 0;
  useFetchStub((url) => {
    if (url.pathname.endsWith('/preview')) return jsonResponse({ confirmationToken: 'secret', scopeVersion: 'v1',
      generation: 0, expiresAt: Date.now() + 300000, deletes: ['旧聊天'], preserves: ['手记与附件'], limitation: '仅本地清理' });
    submissions++; return jsonResponse({ generation: 1, status: 'QUIESCING', completedSteps: [] });
  });
  const React = getReact();
  const { MessageProvider } = await loadModule('/src/components/common/Message.tsx');
  const { AgentResetSection } = await loadModule('/src/components/reset/AgentResetSection.tsx');
  view = await mount(React.createElement(MessageProvider, null, React.createElement(AgentResetSection)));
  await view.click('恢复出厂设置');
  assert.match(document.body.textContent, /当前实际后端/);
  assert.match(document.body.textContent, /手记与附件/);
  const confirm = [...document.querySelectorAll('button')].find(b => b.textContent === '确认清除聊天与记忆');
  await act(async () => confirm.click());
  assert.equal(submissions, 0);
  assert.match(document.body.textContent, /请输入完整文字/);
  const input = document.querySelector('#reset-confirmation');
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(input, '清除聊天与记忆');
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
  });
  await act(async () => confirm.click());
  assert.equal(submissions, 1);
});
