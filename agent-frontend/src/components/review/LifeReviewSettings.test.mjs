import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';
let React, Settings, StatusPage, Card, MessageProvider, stub, view, calls, settings, history, suggestion, nudges, companion, modelPath;
before(async () => {
  await startHarness(); React = getReact();
  ({ LifeReviewSettings: Settings } = await loadModule('/src/components/review/LifeReviewSettings.tsx'));
  ({ LifeStatusPage: StatusPage } = await loadModule('/src/components/review/LifeStatusPage.tsx'));
  ({ LifeContentCard: Card } = await loadModule('/src/components/review/LifeContentCard.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);
beforeEach(() => {
  calls = []; history = [];
  suggestion = { available: false, suggestedHour: null, sampleCount: 1, activeDays: 1, windowDays: 14, hourCounts: Array(24).fill(0), note: '近 14 天统计到 1 个活跃日' };
  nudges = [];
  companion = null;
  settings = { revision: 1, enabled: true, sources: ['JOURNAL'], providerId: 'p', modelName: 'm', timezone: 'Asia/Shanghai',
    historyDays: 7, dailyLimit: 1, proactive: false, feishu: false, idleCheckinDays: 0, authorized: false, policyVersion: 'life-v1', channelAvailable: false, channelLabel: '未连接本人飞书' };
  stub = useFetchStub((url, init) => {
    calls.push({ url, init });
    const path = url.pathname;
    if (path.endsWith('/settings')) {
      if (init?.method === 'PUT') settings = { ...settings, ...JSON.parse(init.body), revision: settings.revision + 1, authorized: false };
      return jsonResponse(settings);
    }
    if (path.endsWith('/grant')) { settings = { ...settings, authorized: true }; return jsonResponse(settings); }
    if (path.endsWith('/habit/suggestion')) return jsonResponse(suggestion);
    if (path.endsWith('/companion')) return jsonResponse(companion);
    if (path.endsWith('/companion/cognition') && init?.method === 'PUT') {
      const body = JSON.parse(init.body);
      companion = { ...companion, cognition: { ...companion.cognition, ...body } };
      return jsonResponse(companion);
    }
    if (path.endsWith('/companion/enable') && init?.method === 'POST') {
      const body = JSON.parse(init.body);
      companion = { ...companion, companionEnabled: body.enabled };
      return jsonResponse(companion);
    }
    if (path.endsWith('/companion/learn') && init?.method === 'POST') return jsonResponse(companion);
    if (path.endsWith('/companion/reach-out') && init?.method === 'POST') return jsonResponse(companion);
    if (path.endsWith('/habit')) return jsonResponse(null);
    if (path.endsWith('/nudges')) return jsonResponse(nudges);
    if (path.includes('/nudges/') && path.endsWith('/feedback')) {
      const decision = JSON.parse(init.body).decision;
      nudges = nudges.map(n => ({ ...n, feedback: decision }));
      return jsonResponse(nudges);
    }
    if (path === '/agent/personal-context') {
      return jsonResponse({ enabled: true, maintenanceEnabled: false, profile: '私密画像内容', source: 'explicit',
        revision: 'r1', estimatedTokens: 6, profileTokenBudget: 300, memoryTokenBudget: 500, totalTokenBudget: 850, maxProfileChars: 12000 });
    }
    if (path === '/agent/personal-context/maintenance') {
      return jsonResponse({ enabled: false, currentRevision: 'r1', lastCheckedAt: null, lastResult: 'never_checked', pendingProposal: null });
    }
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
async function changeInput(input, value) {
  assert.ok(input);
  const proto = input.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  await act(async () => {
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, value);
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
    input.dispatchEvent(new window.Event('change', { bubbles: true }));
  });
}
async function render(props = {}) { view = await mount(React.createElement(MessageProvider, null, React.createElement(Settings, props))); }
async function renderStatus(props = {}) { view = await mount(React.createElement(MessageProvider, null, React.createElement(StatusPage, props))); }

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
test('修改未保存范围会禁用授权和状态跳转，不能借旧授权读新来源', async () => {
  settings.authorized = true; await render({ onOpenStatus() {} });
  await click('配置复盘');
  await act(async () => query('input[aria-label="主动呈现生活复盘"]').click());
  assert.match(document.body.textContent, /未保存更改/);
  const revokeBtn = queryAll('button').find(b => b.textContent === '撤销授权');
  assert.equal(revokeBtn.disabled, true);
  const statusBtn = queryAll('button').find(b => b.textContent === '前往状态');
  assert.equal(statusBtn.disabled, true);
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

test('目标维护位于状态模块内，新增表单只在弹窗出现', async () => {
  await renderStatus();
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
  await renderStatus();
  assert.match(document.body.textContent, /正在生成复盘正文/);
  assert.match(document.body.textContent, /完成后会自动刷新/);
  const prepare = queryAll('button').find(b => b.textContent.includes('正在生成复盘'));
  assert.ok(prepare);
  assert.equal(prepare.disabled, true);
});

test('支持在我的复盘直接设置每日自动生成时间且备注可选', async () => {
  settings.authorized = true;
  await renderStatus();
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

test('今天版本优先展示，查看复用旧版，更新须确认并创建新版本', async () => {
  settings.authorized = true;
  const todayParts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(new Date());
  const today = ['year', 'month', 'day'].map(t => todayParts.find(p => p.type === t)?.value || '').join('-');
  history = [{ id: 'ready-1', day: today, timezone: 'Asia/Shanghai', version: 1, state: 'READY',
    sourceAsOf: '2026-10-30T13:00:00Z', availableAt: '2026-10-30T13:00:00Z', coverage: '', message: '',
    materials: [], facts: [], items: [{ kind: 'FACT_OBSERVATION', text: '已有今日复盘', evidenceIds: ['e1'], uncertainty: '' }], questions: [], deliveries: [] }];
  const yesterday = new Date(new Date(`${today}T00:00:00Z`).getTime() - 86400000).toISOString().slice(0, 10);
  history.unshift({ ...history[0], id: 'yesterday-backfill', day: yesterday });
  await renderStatus();
  assert.match(query('article').getAttribute('aria-label'), new RegExp(today));
  assert.doesNotMatch(query('article').textContent, /今日回顾/);
  await click('准备今天的复盘');
  const prepares = calls.filter(c => c.url.pathname.endsWith('/prepare') && c.init?.method === 'POST');
  assert.equal(prepares.length, 1);
  assert.deepEqual(JSON.parse(prepares[0].init.body), { day: today, expectedVersion: 0, supplement: false });
  await click('更新今天的复盘');
  assert.equal(calls.filter(c => c.url.pathname.endsWith('/prepare')).length, 1);
  await click('确认更新');
  const update = calls.filter(c => c.url.pathname.endsWith('/prepare')).at(-1);
  assert.deepEqual(JSON.parse(update.init.body), { day: today, expectedVersion: 1, supplement: true });
});

test('展示基于互动时间戳的作息建议并支持一键采用，且支持配置久未互动问候与反馈', async () => {
  settings.authorized = true;
  settings.proactive = true;
  settings.feishu = true;
  settings.channelAvailable = true;
  settings.idleCheckinDays = 3;
  suggestion = {
    available: true, suggestedHour: 22, sampleCount: 8, activeDays: 4, windowDays: 14,
    hourCounts: Array(24).fill(0), note: '基于近 14 天 4 个活跃日、共 8 次互动时间戳统计，你在 22:00 前后较为活跃',
  };
  nudges = [
    {
      id: 'nudge-1', kind: 'IDLE_CHECKIN',
      text: '好几天没见你来记录或聊近况了，顺手问候一声。',
      state: 'SENT', createdAt: '2026-10-30T13:00:00Z', sentAt: '2026-10-30T13:00:00Z', feedback: '',
    },
    {
      id: 'nudge-2', kind: 'WEATHER_ALERT',
      text: '【天气变化提醒】杭州当前转为暴雨（18°C，体感 17°C），外出请留意天气变化。',
      state: 'SENT', createdAt: '2026-10-30T14:00:00Z', sentAt: '2026-10-30T14:00:00Z', feedback: '',
    },
  ];
  await renderStatus();

  const details = queryAll('details').find(d => d.querySelector('summary')?.textContent.includes('主动联系'));
  assert.ok(details);
  details.open = true;
  assert.match(document.body.textContent, /天气变化提醒/);
  assert.match(document.body.textContent, /杭州当前转为暴雨/);
  await click('采用建议（22:00）');
  const habitWrites = calls.filter(c => c.url.pathname.endsWith('/habit') && c.init?.method === 'PUT');
  assert.equal(habitWrites.length, 1);
  assert.deepEqual(JSON.parse(habitWrites[0].init.body), {
    expectedRevision: 0, availableHour: 22, source: '采用近 14 天互动活跃时段建议', active: true,
  });

  await click('有帮助');
  const feedbackWrites = calls.filter(c => c.url.pathname.includes('/nudges/nudge-1/feedback') && c.init?.method === 'POST');
  assert.equal(feedbackWrites.length, 1);
  assert.deepEqual(JSON.parse(feedbackWrites[0].init.body), { decision: 'HELPFUL' });
});

test('支出提醒开关依赖财务来源授权与主动飞书，开启后随设置一并保存', async () => {
  settings.proactive = true;
  settings.feishu = true;
  settings.channelAvailable = true;
  await render();
  await click('配置复盘');
  const weatherCheckbox = query('input[aria-label="天气显著变化提醒"]');
  const expenseCheckbox = query('input[aria-label="支出显著变化提醒"]');
  assert.equal(weatherCheckbox.disabled, false);
  assert.equal(expenseCheckbox.disabled, true);

  const financeCheckbox = queryAll('label').find(l => l.textContent.includes('财务数值'))?.querySelector('input');
  assert.ok(financeCheckbox);
  await act(async () => financeCheckbox.click());
  assert.equal(query('input[aria-label="支出显著变化提醒"]').disabled, false);

  await act(async () => query('input[aria-label="天气显著变化提醒"]').click());
  await act(async () => query('input[aria-label="支出显著变化提醒"]').click());
  await click('保存生活复盘设置');

  const putCall = calls.find(c => c.url.pathname.endsWith('/settings') && c.init?.method === 'PUT');
  assert.ok(putCall);
  const payload = JSON.parse(putCall.init.body);
  assert.equal(payload.weatherAlertEnabled, true);
  assert.equal(payload.expenseAlertEnabled, true);
  assert.match(document.body.textContent, /天气提醒/);
  assert.match(document.body.textContent, /支出提醒/);
});

test('服务器探针提醒支持在设置页配置名称与URL，并在状态页展示短消息', async () => {
  settings.proactive = true;
  settings.feishu = true;
  settings.channelAvailable = true;
  nudges = [
    {
      id: 'nudge-server-1', kind: 'SERVER_ALERT',
      text: '【服务器状态提醒】生产主后端健康检查检测到异常（HTTP 503 状态异常），请留意服务可用性。',
      state: 'SENT', createdAt: '2026-10-30T15:00:00Z', sentAt: '2026-10-30T15:00:00Z', feedback: '',
    },
    {
      id: 'nudge-server-2', kind: 'SERVER_RECOVERY',
      text: '【服务器恢复提醒】生产主后端健康检查已恢复正常（HTTP 200），探针服务已重新就绪。',
      state: 'SENT', createdAt: '2026-10-30T15:05:00Z', sentAt: '2026-10-30T15:05:00Z', feedback: '',
    },
  ];
  await render();
  await click('配置复盘');
  const serverCheckbox = query('input[aria-label="服务器只读健康提醒"]');
  assert.equal(serverCheckbox.checked, false);
  await act(async () => serverCheckbox.click());

  const nameInput = query('input[aria-label="服务器标识名称"]');
  const urlInput = query('input[aria-label="服务器探针地址"]');
  assert.ok(nameInput);
  assert.ok(urlInput);

  await changeInput(nameInput, '生产集群网关');
  await changeInput(urlInput, 'https://api.example.com/actuator/health');

  await click('保存生活复盘设置');
  const putCall = calls.find(c => c.url.pathname.endsWith('/settings') && c.init?.method === 'PUT');
  assert.ok(putCall);
  const payload = JSON.parse(putCall.init.body);
  assert.equal(payload.serverAlertEnabled, true);
  assert.equal(payload.serverProbeName, '生产集群网关');
  assert.equal(payload.serverProbeUrl, 'https://api.example.com/actuator/health');

  await view.unmount(); view = null;
  await renderStatus();
  const details = queryAll('details').find(d => d.querySelector('summary')?.textContent.includes('主动联系'));
  assert.ok(details);
  details.open = true;
  assert.match(document.body.textContent, /服务器状态提醒/);
  assert.match(document.body.textContent, /服务器恢复提醒/);
  assert.match(document.body.textContent, /HTTP 503 状态异常/);
});

test('重要邮件提醒支持在设置页配置失败状态，并在状态页展示短消息', async () => {
  settings.mailStatus = '上次邮箱检查失败，请检查连接、加密设置或授权码';
  settings.proactive = true;
  settings.feishu = true;
  settings.channelAvailable = true;
  nudges = [
    {
      id: 'nudge-mail-1', kind: 'MAIL_ALERT',
      text: '【重要邮件提醒】检测到来自「运维监控组」的重要邮件：《生产数据库主从同步延迟》，请留意查收。',
      state: 'SENT', createdAt: '2026-10-30T16:00:00Z', sentAt: '2026-10-30T16:00:00Z', feedback: '',
    },
  ];
  await render();
  await click('配置复盘');
  assert.match(document.body.textContent, /上次邮箱检查失败/);
  const mailCheckbox = query('input[aria-label="重要邮件只读提醒"]');
  assert.equal(mailCheckbox.checked, false);
  await act(async () => mailCheckbox.click());

  await changeInput(query('input[aria-label="IMAP 服务器主机"]'), 'imap.example.com');
  await changeInput(query('input[aria-label="IMAP 端口"]'), '993');
  await changeInput(query('input[aria-label="IMAP 账号"]'), 'ops@example.com');
  await changeInput(query('input[aria-label="IMAP 密码或授权码"]'), 'auth_token_123');
  await changeInput(query('input[aria-label="发件人白名单"]'), 'ops@company.com, alert@domain.com');
  await changeInput(query('input[aria-label="主题关键词"]'), '紧急, 故障, 开票');

  await click('保存生活复盘设置');
  const putCall = calls.find(c => c.url.pathname.endsWith('/settings') && c.init?.method === 'PUT');
  assert.ok(putCall);
  const payload = JSON.parse(putCall.init.body);
  assert.equal(payload.mailAlertEnabled, true);
  assert.equal(payload.imapHost, 'imap.example.com');
  assert.equal(payload.imapPort, 993);
  assert.equal(payload.imapUsername, 'ops@example.com');
  assert.equal(payload.imapPassword, 'auth_token_123');

  await view.unmount(); view = null;
  await renderStatus();
  const details = queryAll('details').find(d => d.querySelector('summary')?.textContent.includes('主动联系'));
  assert.ok(details);
  details.open = true;
  assert.match(document.body.textContent, /重要邮件提醒/);
  assert.match(document.body.textContent, /生产数据库主从同步延迟/);
});

test('状态页展示四维系统性认知、个人画像与经历来源管理，支持编辑保存认知与打开画像弹窗', async () => {
  companion = {
    companionEnabled: false,
    primarySessionId: 'feishu-main-1',
    primarySessionTitle: '飞书主对话',
    activeModelLabel: 'qwen-max',
    feishuConnected: true,
    feishuLabel: '本人飞书',
    cognition: {
      backgroundAndPreferences: '偏好简洁直接、不打扰的交流方式',
      routineAndBusyHours: '通常在 09:00—18:00 集中忙正事，晚间互动最活跃',
      activeFocusAndBlockers: '正在推进主动陪伴模块重构',
      pendingHooksAndCuriosities: '上次聊到跑了五公里，想顺手跟进恢复情况',
      busyStartHour: 9,
      busyEndHour: 18,
      updatedAt: '2026-11-08T12:00:00Z',
    },
    rhythm: {
      companionEnabled: false,
      unansweredCount: 1,
      busyDeferred: true,
      lastOutreachHook: '跑步恢复',
      lastLearnedAt: '2026-11-08T12:00:00Z',
      lastDataFingerprint: 'fp',
      lastOutreachAt: '2026-11-08T10:00:00Z',
      lastUserActiveAt: '2026-11-08T08:00:00Z',
      nextReachAt: '2026-11-08T18:15:00Z',
    },
    pendingDesktopNotices: [],
  };
  await renderStatus();
  assert.match(document.body.textContent, /不定时主动问候与陪伴/);
  assert.match(document.body.textContent, /忙时免打扰顺延中/);
  assert.match(document.body.textContent, /正在推进主动陪伴模块重构/);
  assert.match(document.body.textContent, /已保存个人画像/);
  assert.doesNotMatch(document.body.textContent, /私密画像内容/);

  await click('管理画像');
  assert.equal(query('[aria-label="个人画像内容"]').value, '私密画像内容');
  await act(async () => query('[role="dialog"][aria-label="管理个人画像"] [aria-label="关闭"]').click());

  await click('一键开启主动陪伴');
  const enableCall = calls.find(c => c.url.pathname.endsWith('/companion/enable') && c.init?.method === 'POST');
  assert.ok(enableCall);
  assert.equal(JSON.parse(enableCall.init.body).enabled, true);

  await click('修改认知');
  const focusInput = query('textarea[aria-label="近期主线大事与卡点"]');
  assert.ok(focusInput);
  await changeInput(focusInput, '已完成主动陪伴改造并准备联调');
  await click('保存系统性认知');

  const saveCogCall = calls.find(c => c.url.pathname.endsWith('/companion/cognition') && c.init?.method === 'PUT');
  assert.ok(saveCogCall);
  assert.equal(JSON.parse(saveCogCall.init.body).activeFocusAndBlockers, '已完成主动陪伴改造并准备联调');
  assert.match(document.body.textContent, /已完成主动陪伴改造并准备联调/);
});
