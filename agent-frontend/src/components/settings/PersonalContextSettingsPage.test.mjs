import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, jsonResponse, loadModule, mount, startHarness, stopHarness, useFetchStub } from '../../testing/harness.mjs';

let Page, Provider, view, stub, settings, maintenance, writes;
before(async () => {
  await startHarness();
  ({ PersonalContextSettingsPage: Page } = await loadModule('/src/components/settings/PersonalContextSettingsPage.tsx'));
  ({ MessageProvider: Provider } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);
beforeEach(() => {
  settings = { enabled: true, maintenanceEnabled: false, profile: '私密画像测试内容', source: 'explicit', revision: 'r1',
    estimatedTokens: 10, profileTokenBudget: 300, memoryTokenBudget: 500, totalTokenBudget: 850, maxProfileChars: 12000 };
  maintenance = { enabled: false, currentRevision: 'r1', lastCheckedAt: null, lastResult: 'never_checked', pendingProposal: null };
  writes = [];
  stub = useFetchStub(async (url, init) => {
    const method = init?.method ?? 'GET';
    if (method !== 'GET') writes.push({ path: url.pathname, method, body: init?.body ? JSON.parse(init.body) : null });
    if (url.pathname === '/agent/personal-context/profile') {
      settings = { ...settings, profile: method === 'DELETE' ? '' : JSON.parse(init.body).profile };
      return jsonResponse(settings);
    }
    if (url.pathname === '/agent/personal-context') return jsonResponse(settings);
    if (url.pathname === '/agent/personal-context/maintenance') return jsonResponse(maintenance);
    return jsonResponse(null, 503);
  });
});
afterEach(async () => { await view?.unmount(); stub.restore(); });
async function render() {
  const React = getReact();
  view = await mount(React.createElement(Provider, null, React.createElement(Page)));
}
async function click(text) {
  const button = [...document.querySelectorAll('button')].find(node => node.textContent === text);
  assert.ok(button, text);
  await act(async () => button.click());
}
async function edit(value) {
  const input = document.querySelector('[aria-label="个人画像内容"]');
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
}
async function closeProfile() {
  const close = document.querySelector('[role="dialog"][aria-label="管理个人画像"] [aria-label="关闭"]');
  await act(async () => close.click());
}

test('首页不挂载画像正文，打开后可编辑，关闭隐藏正文并保留未保存草稿', async () => {
  await render();
  assert.match(view.text(), /已保存个人画像/);
  assert.equal(document.querySelector('textarea'), null);
  assert.doesNotMatch(document.body.textContent, /私密画像测试内容/);
  await click('管理画像');
  assert.equal(document.querySelector('[aria-label="个人画像内容"]').value, '私密画像测试内容');
  await edit('未保存的私密草稿');
  await closeProfile();
  assert.equal(document.querySelector('textarea'), null);
  assert.doesNotMatch(document.body.textContent, /未保存的私密草稿/);
  assert.equal(writes.length, 0);
  await click('管理画像');
  assert.equal(document.querySelector('[aria-label="个人画像内容"]').value, '未保存的私密草稿');
  await click('保存画像');
  assert.deepEqual(writes[0].body, { profile: '未保存的私密草稿' });
  await closeProfile();
  assert.equal(document.querySelector('textarea'), null);
});

test('画像提案的摘要、差异和完整正文也只在管理弹窗内展示', async () => {
  maintenance = { ...maintenance, lastResult: 'proposal_ready', pendingProposal: { id: 'p1', baseRevision: 'r1',
    summary: '私密提案摘要', proposedProfile: '私密提案全文', changes: [{ operation: 'UPDATE', section: '偏好',
      before: '私密旧值', after: '私密新值', reason: '私密理由', sourceIds: ['s1'], confidence: 0.8 }] } };
  await render();
  assert.doesNotMatch(document.body.textContent, /私密/);
  assert.equal(document.querySelector('textarea'), null);
  await click('审核画像建议');
  assert.match(document.body.textContent, /私密提案摘要/);
  assert.match(document.body.textContent, /私密旧值/);
  assert.equal(document.querySelector('#proposal-profile').value, '私密提案全文');
  await closeProfile();
  assert.doesNotMatch(document.body.textContent, /私密/);
  assert.equal(writes.length, 0);
});

test('清空画像仍需确认，成功后首页只显示未填写状态', async () => {
  await render(); await click('管理画像'); await click('清空画像');
  assert.equal(writes.length, 0);
  await click('确认清空');
  assert.equal(writes[0].method, 'DELETE');
  assert.equal(document.querySelector('[aria-label="个人画像内容"]').value, '');
  await closeProfile();
  assert.match(view.text(), /尚未填写个人画像/);
  assert.equal(document.querySelector('textarea'), null);
});
