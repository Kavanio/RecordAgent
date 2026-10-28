import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, jsonResponse, loadModule, mount, startHarness, stopHarness, useFetchStub } from '../../testing/harness.mjs';

let Page, Provider, view, stub, settings, documents, detail, operation, calls, failCommand, failSettings;
before(async () => {
  await startHarness();
  ({ ExperienceMemorySettings: Page } = await loadModule('/src/components/memory/ExperienceMemorySettings.tsx'));
  ({ MessageProvider: Provider } = await loadModule('/src/components/common/Message.tsx'));
  const { setAgentGeneration } = await loadModule('/src/services/agentGeneration.ts'); setAgentGeneration(7);
});
after(stopHarness);
beforeEach(() => {
  settings = { version: 0, autoSave: false, autoRecall: false, authorizationRevision: 0,
    authorized: false, remoteAvailable: false, recipientFingerprint: '', allowSave: false, allowRecall: false,
    channels: [], effectiveAt: null, retentionDays: 0, dailyWriteLimit: 0, dailyQueryLimit: 0 };
  documents = []; detail = null; operation = null; calls = []; failCommand = false; failSettings = false;
  stub = useFetchStub(async (url, init) => {
    const method = init?.method ?? 'GET'; const body = init?.body ? JSON.parse(init.body) : null;
    calls.push({ path: url.pathname, cursor: url.searchParams.get('cursor'), method, body, headers: init?.headers });
    const path = url.pathname.replace('/agent/memory', '');
    if (path === '/settings') {
      if (failSettings) return jsonResponse(null, 503);
      if (method === 'PUT') settings = { ...settings, autoSave: body.autoSave, autoRecall: body.autoRecall, version: settings.version + 1 };
      return jsonResponse({ ...settings });
    }
    if (path === '/authorization') {
      if (method === 'POST') settings = { ...settings, ...body, authorized: true, authorizationRevision: 1 };
      else settings = { ...settings, authorized: false, autoSave: false, autoRecall: false, authorizationRevision: 2 };
      return jsonResponse({ revision: settings.authorizationRevision, effectiveAt: '2026-10-28T08:00:00Z' });
    }
    if (path === '/documents') return jsonResponse({ items: documents, nextCursor: null });
    if (path === '/documents/source' && method === 'GET') return jsonResponse(detail);
    if (path === '/documents/source' && method === 'DELETE' || path.endsWith('/corrections')) {
      if (failCommand) throw new Error('请求中断，请核对原任务');
      operation = { id: 'task', sourceId: 'source', kind: method === 'DELETE' ? 'DELETE' : 'CORRECT', version: body.expectedVersion + 1,
        state: 'PENDING', retryAllowed: true, updatedAt: '2026-10-28T08:00:00Z' };
      detail = { ...detail, document: { ...detail.document, state: 'REVOKED', version: operation.version, operationId: 'task' },
        correctedText: body.correctedText ?? null, correctionStatus: body.correctedText ? 'LOCAL_ONLY' : 'NONE' };
      documents = [detail.document];
      return jsonResponse(operation);
    }
    if (path === '/operations/task/retry') {
      operation = { ...operation, state: 'UNKNOWN' }; return jsonResponse(operation);
    }
    if (path === '/operations/task') return jsonResponse(operation);
    throw new Error(`未设置请求桩：${method} ${path}`);
  });
});
afterEach(async () => { await view?.unmount(); view = null; stub.restore(); });
async function render() { const React = getReact(); view = await mount(React.createElement(Provider, null, React.createElement(Page))); }
async function click(text, scope = document) {
  const button = [...scope.querySelectorAll('button')].find((node) => node.textContent === text);
  assert.ok(button, text); await act(async () => button.click());
}
async function change(selector, value) {
  const input = document.querySelector(selector); assert.ok(input, selector);
  const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  await act(async () => {
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
}
function seedSource() {
  documents = [{ id: 'source', version: 0, occurredAt: '2026-10-28T08:00:00Z', channel: 'DESKTOP', state: 'CAPTURED',
    syncState: 'PENDING', deletionState: 'TRACKED', corrected: false, operationId: null }];
  detail = { document: documents[0], originalStatus: 'AVAILABLE', originalText: '合成原文保持不变', correctedText: null, correctionStatus: 'NONE' };
}

test('未接入时展示关闭状态及空来源，禁用授权，不发送外部写入', async () => {
  await render(); assert.match(view.text(), /未接入/); assert.match(view.text(), /暂无经历来源/);
  assert.equal([...document.querySelectorAll('button')].find(b => b.textContent === '查看并授权').disabled, true);
  assert.ok([...document.querySelectorAll('[role="switch"]')].every(s => s.getAttribute('aria-checked') === 'false'));
  assert.ok(calls.every(call => call.method === 'GET'));
});

test('用途与渠道须显式确认；授权成功不自动开启保存召回', async () => {
  settings.remoteAvailable = true; settings.recipientFingerprint = 'synthetic-chain'; await render();
  await click('查看并授权'); await click('确认授权范围');
  assert.match(document.body.textContent, /请选择用途和渠道/);
  assert.equal(calls.filter(c => c.method === 'POST').length, 0);
  await act(async () => document.querySelector('input[aria-label="允许保存新经历"]').click());
  const labels = [...document.querySelectorAll('label')];
  await act(async () => labels.find(n => n.textContent === '桌面普通聊天').querySelector('input').click());
  await change('#memory-retention', '30'); await change('#memory-writes', '5'); await change('#memory-queries', '5');
  await act(async () => [...document.querySelectorAll('label')].find(n => n.textContent.startsWith('我已确认')).querySelector('input').click());
  await click('确认授权范围');
  const call = calls.find(c => c.path.endsWith('/authorization') && c.method === 'POST');
  assert.deepEqual(call.body.channels, ['DESKTOP']); assert.equal(call.body.allowRecall, false);
  assert.equal(call.headers.get('X-Agent-Generation'), '7');
  assert.ok([...document.querySelectorAll('[role="switch"]')].every(s => s.getAttribute('aria-checked') === 'false'));
});

test('保存召回各自控制；撤权须确认且 DELETE 使用 JSON 和当前 revision', async () => {
  settings = { ...settings, authorized: true, remoteAvailable: true, allowSave: true, allowRecall: true, authorizationRevision: 4, recipientFingerprint: 'synthetic', channels: ['DESKTOP'] };
  await render();
  await act(async () => document.querySelector('[role="switch"][aria-label="自动保存新经历"]').click());
  const save = calls.find(c => c.method === 'PUT'); assert.equal(save.body.autoSave, true); assert.equal(save.body.autoRecall, false);
  await click('撤销授权'); assert.equal(calls.filter(c => c.method === 'DELETE').length, 0);
  await click('确认撤销');
  const revoke = calls.find(c => c.method === 'DELETE'); assert.equal(revoke.body.expectedRevision, 4); assert.ok(revoke.body.requestId);
  assert.match(document.body.textContent, /远端数据仍须单独删除/);
});

test('列表不展开正文或冒充已记住；点击详情才读取原文，取消删除不写入', async () => {
  seedSource(); await render(); assert.match(view.text(), /等待同步/); assert.doesNotMatch(view.text(), /合成原文/);
  await click('查看来源'); assert.match(document.body.textContent, /合成原文保持不变/);
  await click('删除记忆来源'); await click('取消', [...document.querySelectorAll('[role="dialog"]')].find(d => d.textContent.includes('确认删除记忆来源')));
  assert.equal(calls.filter(c => c.method === 'DELETE').length, 0);
});

test('纠正先确认且请求失败可复用同一幂等 ID；任务未知不显示远端删除成功', async () => {
  seedSource(); await render(); await click('查看来源'); await change('#memory-correction', '合成纠正文本');
  await click('保存纠正并停用旧来源'); failCommand = true; await click('确认操作');
  const first = calls.find(c => c.path.endsWith('/corrections'));
  failCommand = false; await click('确认操作');
  const requests = calls.filter(c => c.path.endsWith('/corrections'));
  assert.equal(requests.length, 2); assert.equal(requests[1].body.requestId, first.body.requestId);
  assert.match(document.body.textContent, /等待核对远端删除/);
  assert.match(document.body.textContent, /合成原文保持不变/);
  await click('继续核对删除'); assert.match(document.body.textContent, /远端删除结果未知/);
  const retries = calls.filter(c => c.path.endsWith('/retry')).length;
  await click('刷新详情'); assert.equal(calls.filter(c => c.path.endsWith('/retry')).length, retries);
  assert.match(document.body.textContent, /远端删除结果未知/);
});

test('页面重新挂载可恢复来源原任务，读取状态不重发删除', async () => {
  seedSource(); operation = { id: 'task', sourceId: 'source', kind: 'DELETE', version: 1, state: 'FAILED', retryAllowed: true, updatedAt: '2026-10-28T08:00:00Z' };
  detail.document = { ...detail.document, operationId: 'task', state: 'REVOKED', version: 1 };
  documents = [detail.document]; await render(); await click('查看来源');
  assert.match(document.body.textContent, /远端删除核对失败/);
  assert.ok(calls.every(c => c.method === 'GET'));
});

test('设置加载失败提供就地重试，来源列表仍可独立使用', async () => {
  failSettings = true; await render(); assert.match(view.text(), /设置读取失败/); assert.match(view.text(), /暂无经历来源/);
  failSettings = false; await click('重试读取设置'); assert.doesNotMatch(view.text(), /设置读取失败/);
});
