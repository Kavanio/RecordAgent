import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';
let React, Settings, MessageProvider, view, stub, calls, settings;
before(async () => {
  await startHarness(); React = getReact();
  ({ ReportReviewSettings: Settings } = await loadModule('/src/components/review/ReportReviewSettings.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);
beforeEach(() => {
  calls = [];
  settings = { enabled: true, authorized: false, generationAvailable: false, capability: 'FOUNDATION_ONLY',
    policyVersion: 'report-review-v1', periods: ['DAILY_REPORT'], domains: ['STUDY'], historyDays: 30,
    dailyLimit: 1, providerId: 'p', modelName: 'm', connectionFingerprint: '', revision: 3, grant: null };
  stub = useFetchStub((url, init) => {
    calls.push({ url, init });
    const path = url.pathname;
    if (path.endsWith('/agent/reviews/settings')) {
      if (init?.method === 'PUT') settings = { ...settings, ...JSON.parse(init.body), revision: settings.revision + 1, authorized: false };
      return jsonResponse(settings);
    }
    if (path.endsWith('/memory')) return jsonResponse({ actions: [], observations: [] });
    if (path.endsWith('/grants')) {
      settings = { ...settings, authorized: true, grant: { id: 'g', policyVersion: 'report-review-v1', state: 'ACTIVE',
        revision: 1, periods: ['DAILY_REPORT'], domains: ['STUDY'], historyDays: 30, dailyLimit: 1,
        connectionFingerprint: '', createdAt: '', revokedAt: null } };
      return jsonResponse(settings.grant);
    }
    return jsonResponse({ providers: [{ id: 'p', name: '测试连接', isEnabled: true, models: [{ id: 'm', name: 'm', providerId: 'p' }] }] });
  });
});
afterEach(async () => { await view?.unmount(); view = null; stub.restore(); });
const query = selector => document.body.querySelector(selector);
const queryAll = selector => [...document.body.querySelectorAll(selector)];
async function click(label) { const button = queryAll('button').find(b => b.textContent.trim() === label); assert.ok(button, label); await act(async () => button.click()); }
async function render() { view = await mount(React.createElement(MessageProvider, null, React.createElement(Settings))); }
async function setValue(selector, value) {
  await act(async () => {
    const node = query(selector);
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(node, value);
    node.dispatchEvent(new window.Event('input', { bubbles: true }));
  });
}

test('首页只显示状态摘要，配置项全部收在弹窗里', async () => {
  await render();
  assert.equal(query('input[aria-label="最多回看天数"]'), null);
  assert.match(document.body.textContent, /回看30天/);
  await click('配置点评');
  assert.ok(query('input[aria-label="最多回看天数"]'));
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
});

test('配置只改草稿，取消不改已保存值也不授权', async () => {
  await render(); await click('配置点评');
  await setValue('input[aria-label="每天自动生成上限"]', '3');
  await click('取消');
  assert.equal(query('[role="dialog"]'), null);
  await click('配置点评');
  assert.equal(query('input[aria-label="每天自动生成上限"]').value, '1');
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
});

test('授权需明确勾选，只提交当前策略版本', async () => {
  await render(); await click('核对并授权');
  const approve = queryAll('button').find(b => b.textContent.trim() === '授权');
  assert.equal(approve.disabled, true);
  const consent = queryAll('input[type="checkbox"]').at(-1);
  await act(async () => consent.click());
  await click('授权');
  const writes = calls.filter(c => c.init?.method === 'POST');
  assert.equal(writes.length, 1);
  assert.ok(writes[0].url.pathname.endsWith('/grants'));
  assert.deepEqual(JSON.parse(writes[0].init.body), { expectedSettingsRevision: 3, policyVersion: 'report-review-v1', confirmed: true });
});

test('未授权时不能试生成，授权后预览降级只提示未生成', async () => {
  await render();
  assert.equal(queryAll('button').find(b => b.textContent.trim() === '试生成一次').disabled, true);
  await click('核对并授权');
  await act(async () => queryAll('input[type="checkbox"]').at(-1).click());
  await click('授权');
  stub.respond((url, init) => {
    calls.push({ url, init });
    if (url.pathname.endsWith('/agent/reviews/settings')) return jsonResponse(settings);
    if (url.pathname.endsWith('/memory')) return jsonResponse({ actions: [], observations: [] });
    if (url.pathname.endsWith('/preview')) return jsonResponse({ state: 'DEGRADED', message: '模型不可用，本次未生成',
      observations: [], actions: [], followUps: [] });
    return jsonResponse({ providers: [] });
  });
  await click('试生成一次'); await click('确认生成');
  assert.match(document.body.textContent, /模型不可用，本次未生成/);
  assert.doesNotMatch(document.body.textContent, /预览已生成/);
});
