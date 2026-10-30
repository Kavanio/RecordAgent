import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';
let React, Settings, Card, MessageProvider, stub, view, calls, settings, history, modelPath;
before(async () => {
  await startHarness(); React = getReact();
  ({ LifeReviewSettings: Settings } = await loadModule('/src/components/review/LifeReviewSettings.tsx'));
  ({ LifeContentCard: Card } = await loadModule('/src/components/review/LifeContentCard.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);
beforeEach(() => {
  calls = []; history = [];
  settings = { revision: 1, enabled: true, sources: ['JOURNAL'], providerId: 'p', modelName: 'm', timezone: 'Asia/Shanghai',
    historyDays: 7, dailyLimit: 1, proactive: false, feishu: false, authorized: false, policyVersion: 'life-v1', channelAvailable: false, channelLabel: '未连接本人飞书' };
  stub = useFetchStub((url, init) => {
    calls.push({ url, init });
    const path = url.pathname;
    if (path.endsWith('/settings')) {
      if (init?.method === 'PUT') settings = { ...settings, ...JSON.parse(init.body), revision: settings.revision + 1, authorized: false };
      return jsonResponse(settings);
    }
    if (path.endsWith('/grant')) { settings = { ...settings, authorized: true }; return jsonResponse(settings); }
    if (path.endsWith('/habit')) return jsonResponse(null);
    if (path.endsWith('/goals')) return jsonResponse([]);
    if (path.endsWith('/candidates') || path.endsWith('/archives')) return jsonResponse([]);
    if (path.endsWith('/life-reviews')) return jsonResponse(history);
    modelPath = path;
    return jsonResponse({ providers: [{ id: 'p', name: '测试连接', isEnabled: true, models: [{ id: 'm', name: 'm' }] }] });
  });
});
afterEach(async () => { await view?.unmount(); view = null; stub.restore(); });
const query = selector => document.body.querySelector(selector);
const queryAll = selector => [...document.body.querySelectorAll(selector)];
async function click(label) { const button = queryAll('button').find(b => b.textContent === label); assert.ok(button, label); await act(async () => button.click()); }
async function render() { view = await mount(React.createElement(MessageProvider, null, React.createElement(Settings))); }
test('首次读取不授权、不生成、不发送，明确展示独立正文范围', async () => {
  await render();
  assert.equal(query('input'), null);
  await click('配置复盘');
  assert.match(document.body.textContent, /每日手记正文/); assert.match(document.body.textContent, /普通聊天用户原文/);
  assert.match(document.body.textContent, /未授权/);
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
  assert.ok(query('input[aria-label="生活复盘飞书投递"]').disabled);
  assert.ok(modelPath);
});
test('打开授权弹窗确认后只发送当前life-v1许可', async () => {
  await render();
  await click('核对并授权');
  await click('确认授权生活复盘');
  const writes = calls.filter(c => c.init?.method === 'POST');
  assert.equal(writes.length, 1); assert.ok(writes[0].url.pathname.endsWith('/grant'));
  assert.deepEqual(JSON.parse(writes[0].init.body), { expectedRevision: 1, policyVersion: 'life-v1', confirmed: true });
  assert.match(document.body.textContent, /当前保存版本已授权/);
});
test('修改未保存范围会禁用授权和生成，不能借旧授权读新来源', async () => {
  settings.authorized = true; await render();
  await click('配置复盘');
  await act(async () => query('input[aria-label="主动呈现生活复盘"]').click());
  assert.match(document.body.textContent, /未保存更改/);
  const prepare = queryAll('button').find(b => b.textContent === '准备今天的复盘');
  assert.equal(prepare.disabled, true);
  assert.equal(calls.filter(c => c.init?.method === 'POST').length, 0);
});
test('未知飞书结果显示人工核对且没有补发按钮，原文来源与版本可见', async () => {
  const c = { id: 'id', themeId: 'theme', day: '2026-10-30', version: 1, state: 'READY',
    sourceAsOf: '2026-10-30T13:00:00Z', availableAt: '2026-10-30T13:00:00Z', coverage: '仅部分近期记录', message: '',
    materials: [{ id: 'ev-1', sourceId: 'record-1', kind: 'JOURNAL', occurredAt: '2026-10-30', text: '测试手记', truncated: false }],
    items: [], questions: ['还有什么值得留下？可以跳过。'], deliveries: [{ id: 'd', channel: 'FEISHU', state: 'UNKNOWN', attempts: 1 }] };
  let actions = 0;
  view = await mount(React.createElement(Card, { content: c, timezone: 'Asia/Shanghai', busy: false, authorized: true, feishu: true,
    act: async () => { actions++; } }));
  assert.match(document.body.textContent, /v1/); assert.doesNotMatch(document.body.textContent, /record-1/); assert.match(document.body.textContent, /不会自动重发/);
  assert.ok(!queryAll('button').some(b => b.textContent.includes('发送') || b.textContent.includes('重试')));
  assert.equal(actions, 0);
});

test('取消配置丢弃草稿，再打开恢复已保存值，且没有写请求', async () => {
  settings.authorized = true; await render();
  await click('配置复盘');
  await act(async () => query('input[aria-label="主动呈现生活复盘"]').click());
  await click('取消');
  assert.equal(query('[role="dialog"]'), null);
  assert.equal(query('input'), null);
  await click('配置复盘');
  assert.equal(query('input[aria-label="主动呈现生活复盘"]').checked, false);
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
});

test('保存失败保留配置弹窗与用户输入，不隐式授权', async () => {
  await render(); await click('配置复盘');
  stub.respond((url, init) => { calls.push({ url, init }); return jsonResponse(null, 400); });
  await click('保存生活复盘设置');
  assert.ok(query('[role="dialog"][aria-label="配置生活复盘"]'));
  assert.equal(query('input[aria-label="生活复盘时区"]').value, 'Asia/Shanghai');
  assert.equal(calls.filter(c => c.url.pathname.endsWith('/grant')).length, 0);
});

test('目标维护在生活复盘内，新增表单只在弹窗出现', async () => {
  await render();
  assert.equal(query('input[aria-label="目标名称"]'), null);
  assert.match(document.body.textContent, /还没有设定目标/);
  await click('新增目标');
  assert.ok(query('input[aria-label="目标名称"]'));
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
});


test('成功保存关闭配置弹窗并使旧授权失效，不自动重新授权', async () => {
  settings.authorized = true; await render(); await click('配置复盘');
  await act(async () => query('input[aria-label="主动呈现生活复盘"]').click());
  await click('保存生活复盘设置');
  assert.equal(query('[role="dialog"]'), null);
  assert.equal(query('input'), null);
  assert.match(document.body.textContent, /未授权/);
  const writes = calls.filter(c => c.init?.method && c.init.method !== 'GET');
  assert.equal(writes.length, 1); assert.equal(writes[0].init.method, 'PUT');
  assert.equal(JSON.parse(writes[0].init.body).proactive, true);
});

test('补充输入仅在弹窗打开后出现，关闭不生成新版本', async () => {
  let actions = 0;
  const content = { id: 'c', day: '2026-10-30', version: 1, state: 'READY', sourceAsOf: '2026-10-30T13:00:00Z',
    availableAt: '2026-10-30T13:00:00Z', coverage: '部分材料', message: '', materials: [], facts: [], items: [], questions: [], deliveries: [] };
  view = await mount(React.createElement(Card, { content, timezone: 'Asia/Shanghai', busy: false, authorized: true, feishu: false,
    act: async () => { actions++; } }));
  assert.equal(query('textarea'), null);
  await click('补充经历');
  assert.ok(query('[role="dialog"] textarea'));
  await act(async () => query('button[aria-label="关闭"]').click());
  assert.equal(query('textarea'), null); assert.equal(actions, 0);
});

test('失败版本明确显示未生成，诊断默认折叠且不提供飞书发送', async () => {
  const content = { id: 'failed', day: '2026-10-30', version: 1, state: 'DEGRADED', sourceAsOf: '2026-10-30T13:00:00Z',
    availableAt: '2026-10-30T13:00:00Z', providerId: 'internal-provider', modelName: 'test-model', coverage: 'technical-budget',
    message: '模型请求超时', materials: [], facts: [{ id: 'f1', metric: 'asset_balance_book_reconstruction', value: '45076', unit: 'MONEY_MINOR', currency: 'CNY', period: '2026-10-30' }],
    items: [], questions: [], deliveries: [] };
  view = await mount(React.createElement(Card, { content, timezone: 'Asia/Shanghai', busy: false, authorized: true, feishu: true, act: async () => {} }));
  assert.match(document.body.textContent, /本次没有生成复盘正文/);
  assert.doesNotMatch(document.body.textContent, /450\.76 元/);
  await click('参考记录');
  assert.match(query('[role="dialog"]').textContent, /450\.76 元/);
  await act(async () => query('button[aria-label="关闭抽屉"]').click());
  await click('版本信息');
  assert.ok(!queryAll('button').some(b => b.textContent.includes('发送')));
  const diagnostics = queryAll('details').find(d => d.querySelector('summary').textContent.includes('技术排查'));
  assert.equal(diagnostics.open, false);
  assert.match(diagnostics.textContent, /technical-budget/);
  assert.match(query('[role="dialog"]').textContent, /模型请求超时/);
});


test('参考记录仅在抽屉读取快照，分类过滤并兼容Java日期；关闭回焦且不调用接口', async () => {
  const content = { id: 'snapshot', day: '2026-10-30', timezone: 'Asia/Shanghai', version: 2, state: 'READY',
    sourceAsOf: '2026-10-30T02:42:59.938483Z', availableAt: '2026-10-30T04:00:00Z',
    coverage: '窗口=2026-10-24至2026-10-30', message: '', facts: [], items: [], questions: [], deliveries: [],
    materials: [
      { id: 'chat-1', kind: 'CHAT', sourceId: 'private-id', occurredAt: '2026-10-30T10:42:59.938483+08:00[Asia/Shanghai]', text: '聊天测试原文', truncated: true },
      { id: 'journal-1', kind: 'JOURNAL', sourceId: 'journal-id', occurredAt: '2026-10-29', text: '手记测试原文', truncated: false },
      { id: 'goal-1', kind: 'USER_GOAL', sourceId: 'goal-id', occurredAt: 'invalid', text: '目标测试原文', truncated: false },
    ] };
  view = await mount(React.createElement(Card, { content, timezone: 'UTC', busy: false, authorized: false, feishu: false, act: async () => {} }));
  assert.doesNotMatch(document.body.textContent, /聊天测试原文|private-id/);
  const trigger = queryAll('button').find(b => b.textContent === '参考记录');
  trigger.focus(); await click('参考记录');
  const dialog = query('[role="dialog"]');
  assert.match(dialog.textContent, /2026-10-24 至 2026-10-30/);
  assert.match(dialog.textContent, /10:42:59/);
  assert.match(dialog.textContent, /时间未记录/);
  assert.doesNotMatch(dialog.textContent, /Invalid Date|private-id/);
  assert.match(dialog.textContent, /不是完整聊天记录/);
  await click('聊天 · 1');
  assert.match(dialog.textContent, /聊天测试原文/);
  assert.match(dialog.textContent, /原文未完整读取/);
  assert.doesNotMatch(dialog.textContent, /手记测试原文|目标测试原文/);
  await act(async () => window.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' })));
  assert.equal(query('[role="dialog"]'), null);
  assert.equal(document.activeElement, trigger);
  await click('参考记录');
  assert.match(query('[role="dialog"]').textContent, /手记测试原文/);
  assert.equal(calls.length, 0);
});

test('准备中版本显示等待生成提示并禁用重复点击', async () => {
  settings.authorized = true;
  history = [{ id: 'pending-1', day: '2026-10-30', timezone: 'Asia/Shanghai', version: 4, state: 'PENDING',
    sourceAsOf: '2026-10-30T13:00:00Z', availableAt: '2026-10-30T13:00:00Z', coverage: '', message: '',
    materials: [], facts: [], items: [], questions: [], deliveries: [] }];
  await render();
  assert.match(document.body.textContent, /正在生成复盘正文/);
  assert.match(document.body.textContent, /完成后会自动刷新/);
  const prepare = queryAll('button').find(b => b.textContent.includes('正在生成复盘'));
  assert.ok(prepare);
  assert.equal(prepare.disabled, true);
});

test('支持在我的复盘直接设置每日自动生成时间且备注可选', async () => {
  settings.authorized = true;
  await render();
  await click('设置每日时间（21:00）');
  const select = query('select[aria-label="生活复盘作息小时"]');
  assert.ok(select);
  await act(async () => {
    select.value = '20';
    select.dispatchEvent(new window.Event('change', { bubbles: true }));
  });
  await click('保存时间');
  const writes = calls.filter(c => c.url.pathname.endsWith('/habit') && c.init?.method === 'PUT');
  assert.equal(writes.length, 1);
  assert.deepEqual(JSON.parse(writes[0].init.body), { expectedRevision: 0, availableHour: 20, source: '', active: true });
});
