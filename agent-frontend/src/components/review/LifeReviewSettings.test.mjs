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
    if (path.endsWith('/settings')) return jsonResponse(settings);
    if (path.endsWith('/grant')) { settings = { ...settings, authorized: true }; return jsonResponse(settings); }
    if (path.endsWith('/habit')) return jsonResponse(null);
    if (path.endsWith('/candidates') || path.endsWith('/archives')) return jsonResponse([]);
    if (path.endsWith('/life-reviews')) return jsonResponse(history);
    modelPath = path;
    return jsonResponse({ providers: [{ id: 'p', name: '测试连接', isEnabled: true, models: [{ id: 'm', name: 'm' }] }] });
  });
});
afterEach(async () => { await view?.unmount(); view = null; stub.restore(); });
async function render() { view = await mount(React.createElement(MessageProvider, null, React.createElement(Settings))); }
test('首次读取不授权、不生成、不发送，明确展示独立正文范围', async () => {
  await render();
  assert.match(view.text(), /每日手记正文/); assert.match(view.text(), /普通聊天用户原文/);
  assert.match(view.text(), /未授权/);
  assert.ok(calls.every(c => !c.init?.method || c.init.method === 'GET'));
  assert.ok(view.query('input[aria-label="生活复盘飞书投递"]').disabled);
  assert.ok(modelPath);
});
test('未勾选不能授权，勾选后只发送当前life-v1许可', async () => {
  await render();
  const button = view.queryAll('button').find(b => b.textContent === '确认授权生活复盘');
  assert.equal(button.disabled, true);
  const consent = view.queryAll('input[type="checkbox"]').at(-1);
  await act(async () => consent.click());
  await view.click('确认授权生活复盘');
  const writes = calls.filter(c => c.init?.method === 'POST');
  assert.equal(writes.length, 1); assert.ok(writes[0].url.pathname.endsWith('/grant'));
  assert.deepEqual(JSON.parse(writes[0].init.body), { expectedRevision: 1, policyVersion: 'life-v1', confirmed: true });
  assert.match(view.text(), /当前保存版本已授权/);
});
test('修改未保存范围会禁用授权和生成，不能借旧授权读新来源', async () => {
  settings.authorized = true; await render();
  await act(async () => view.query('input[aria-label="主动呈现生活复盘"]').click());
  assert.match(view.text(), /未保存更改/);
  const prepare = view.queryAll('button').find(b => b.textContent === '准备今天的复盘');
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
  assert.match(view.text(), /v1/); assert.match(view.text(), /record-1/); assert.match(view.text(), /不会自动重发/);
  assert.ok(!view.queryAll('button').some(b => b.textContent.includes('发送') || b.textContent.includes('重试')));
  assert.equal(actions, 0);
});


test('人工事件必须选择现有版本和真实依据，记录候选不直接发送或重新生成', async () => {
  settings.authorized = true; settings.proactive = true;
  history = [{ id: 'content-1', day: '2026-10-30', timezone: 'Asia/Shanghai', version: 1, state: 'READY', sourceAsOf: '2026-10-30T13:00:00Z',
    availableAt: '2026-10-30T13:00:00Z', coverage: '有限资料', message: '', items: [], questions: [], deliveries: [], facts: [],
    materials: [{ id: 'journal-1', kind: 'JOURNAL', sourceId: 'record-1', occurredAt: '2026-10-30', text: '虚构的新信息', truncated: false }] }];
  await render();
  const button = view.queryAll('button').find(b => b.textContent === '加入主动候选');
  assert.equal(button.disabled, true);
  const topic = view.query('input[aria-label="主动事件主题"]');
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(topic, '学习状态');
    topic.dispatchEvent(new window.Event('input', { bubbles: true }));
  });
  async function select(label, value) {
    await act(async () => { const node = view.query(`select[aria-label="${label}"]`); node.value = value; node.dispatchEvent(new window.Event('change', { bubbles: true })); });
  }
  await select('主动事件来源版本', 'content-1');
  await select('主动事件证据', 'journal-1');
  await view.click('加入主动候选');
  const writes = calls.filter(c => c.init?.method === 'POST');
  assert.equal(writes.length, 1); assert.ok(writes[0].url.pathname.endsWith('/candidates'));
  assert.deepEqual(JSON.parse(writes[0].init.body), { topic: '学习状态', contentId: 'content-1', evidenceId: 'journal-1', eventKind: 'EVENT' });
});
