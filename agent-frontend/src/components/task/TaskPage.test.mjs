import assert from 'node:assert/strict';
import { test, before, after, beforeEach, afterEach } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

// 真实 React 组件与内存 DOM；HTTP 全部拦截，不访问用户后端。
let server, dom, React, createRoot, TaskPage, TaskReminderWindow, StateContext, MessageProvider, newAutomation;
let root, container, snapshot, requests, historyStatus;
const originalFetch = globalThis.fetch;
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'Event', 'MouseEvent']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
  ({ TaskPage } = await server.ssrLoadModule('/src/components/task/TaskPage.tsx'));
  ({ TaskReminderWindow } = await server.ssrLoadModule('/src/components/task/TaskReminderWindow.tsx'));
  ({ AutomationStateContext: StateContext } = await server.ssrLoadModule('/src/context/automationState.ts'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
  ({ newAutomation } = await server.ssrLoadModule('/src/features/automation/presentation.ts'));
});
after(async () => { await server?.close(); dom?.window.close(); globalThis.fetch = originalFetch; });
beforeEach(async () => {
  requests = []; historyStatus = 'PENDING';
  snapshot = { tasks: ['A', 'B'].map((id, i) => ({ id, version: 1, status: i ? 'PAUSED' : 'ENABLED', nextAt: null, activeSeconds: 0,
    spec: { ...newAutomation(), title: `任务${id}`, content: `内容${id}` } })), pending: [] };
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), ...options });
    let data = {};
    if (String(url).endsWith('/runs')) data = [{ id: 'run', taskId: 'A', title: '任务A', content: '内容A', createdAt: 0,
      status: 'SUCCESS', desktopStatus: 'SUBMITTED', emailStatus: historyStatus, confirmation: 'NONE', error: '' }];
    return { ok: true, json: async () => ({ code: 200, data }) };
  };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await render();
});
afterEach(async () => { await React.act(async () => root?.unmount()); container?.remove(); });
async function render(connected = true) {
  await React.act(async () => root.render(React.createElement(MessageProvider, null,
    React.createElement(StateContext.Provider, { value: { snapshot, connected, activity: null } },
      React.createElement(TaskPage, { onOpenSettings() {} })))));
}
async function click(text, index = 0) {
  const button = [...document.querySelectorAll('button')].filter(b => (b.getAttribute('aria-label') || b.textContent) === text)[index];
  assert.ok(button, `找不到按钮 ${text}`);
  await React.act(async () => button.click());
}
async function input(id, value) {
  const element = document.getElementById(id);
  await React.act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(element, value);
    element.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
  });
}
test('暂停筛选展示 PAUSED 任务', async () => {
  await click('已暂停');
  assert.match(container.textContent, /任务B/);
  assert.doesNotMatch(container.textContent, /任务A/);
});
test('从 A 切到 B 后保存 B 的内容和 ID', async () => {
  await click('编辑', 0);
  assert.equal(document.getElementById('task-title').value, '任务A');
  await click('编辑', 1);
  assert.equal(document.getElementById('task-title').value, '任务B');
  await React.act(async () => document.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  const request = requests.find(r => r.method === 'PUT');
  assert.ok(request.url.endsWith('/B'));
  assert.equal(JSON.parse(request.body).spec.content, '内容B');
});
test('未保存修改切换需显式放弃，取消保留草稿', async () => {
  await click('编辑', 0); await input('task-title', '修改后的A'); await click('编辑', 1);
  assert.match(document.body.textContent, /放弃未保存的修改/);
  await click('继续编辑');
  assert.equal(document.getElementById('task-title').value, '修改后的A');
  await click('编辑', 1); await click('放弃并继续');
  assert.equal(document.getElementById('task-title').value, '任务B');
});
test('执行状态 SSE 快照变化刷新已打开的历史', async () => {
  await click('执行历史', 0);
  const drawer = [...document.querySelectorAll('[role="dialog"]')].find(element => element.textContent.includes('任务A · 执行历史'));
  assert.ok(drawer);
  assert.equal(drawer.getAttribute('aria-modal'), 'true');
  assert.ok(drawer.querySelector('[role="img"][aria-label="邮件：等待投递"]'));
  historyStatus = 'SUBMITTED'; snapshot = { ...snapshot }; await render();
  assert.ok(drawer.querySelector('[role="img"][aria-label="邮件：已提交"]'));
  await click('关闭抽屉');
  assert.equal([...document.querySelectorAll('[role="dialog"]')].some(element => element.textContent.includes('任务A · 执行历史')), false);
});
test('任务页不重复展示待确认黄色提醒', async () => {
  snapshot = { ...snapshot, pending: [{ id: 'r', title: '久坐提醒', content: '起来活动', confirmation: 'WAITING' }] };
  await render();
  assert.doesNotMatch(container.textContent, /久坐提醒 · 待确认/);
});

test('立即执行确认弹框展示任务详情与渠道，支持取消与确认执行', async () => {
  await click('立即执行', 0);
  assert.match(document.body.textContent, /立即执行任务/);
  assert.match(document.body.textContent, /任务A/);
  assert.match(document.body.textContent, /触发渠道/);
  assert.match(document.body.textContent, /将通过上述已开启的渠道发送真实通知或邮件/);

  await click('取消');
  assert.equal(requests.some(r => r.method === 'POST' && r.url.endsWith('/A/run')), false);

  await click('立即执行', 0);
  await click('确认执行');
  await React.act(async () => {
    await new Promise(resolve => setTimeout(resolve, 20));
  });
  assert.ok(requests.some(r => r.method === 'POST' && r.url.endsWith('/A/run')));
});




test('久坐弹框确认后停止声音，倒计时结束确认并播放提示音', async () => {
  const reminder = document.createElement('div'); document.body.append(reminder);
  const reminderRoot = createRoot(reminder);
  const run = { id: 'rest-run', taskId: 'A', title: '久坐提醒', content: '起来活动', confirmation: 'WAITING', sound: true, createdAt: Date.now() };
  const reminderSnapshot = { tasks: [{ ...snapshot.tasks[0], spec: { ...snapshot.tasks[0].spec, kind: 'SEDENTARY', breakMinutes: 1 } }], pending: [run] };
  const calls = [];
  const previousStorage = globalThis.localStorage;
  const previousNow = Date.now;
  let clock = previousNow();
  Date.now = () => clock;
  globalThis.localStorage = window.localStorage;
  globalThis.isTauri = true;
  let plays = 0;
  window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
    calls.push({ command, args });
    if (command === 'task_reminder_configuration') return { preferences: { restLock: true, soundId: 'builtin:bell', repeatSeconds: 4 }, sounds: [] };
    if (command === 'task_reminder_audio') {
      if (args.action === 'supported') return true;
      if (args.action === 'play') { plays++; return true; }
      if (args.action === 'status') return plays === 1;
      return false;
    }
  } };
  const locks = () => calls.filter(value => value.command === 'sync_task_rest_lock').map(value => value.args.remainingSeconds);
  try {
    await React.act(async () => reminderRoot.render(React.createElement(StateContext.Provider, { value: { snapshot: reminderSnapshot, connected: true, activity: null } }, React.createElement(TaskReminderWindow))));
    assert.equal(plays, 1);
    assert.ok(locks().every(seconds => seconds === 0), '确认前不能遮挡桌面');
    const button = [...reminder.querySelectorAll('button')].find(value => value.textContent === '确认并开始休息');
    await React.act(async () => button.click());
    assert.ok(calls.some(value => value.command === 'task_reminder_audio' && value.args.action === 'stop'));
    assert.equal(locks().at(-1), 60, '确认后才开始锁定桌面');
    assert.match(reminder.textContent, /休息中/);
    assert.equal(requests.filter(value => value.url.endsWith('/rest-run/confirm')).length, 0);
    assert.equal(plays, 1);
    await React.act(async () => reminderRoot.render(React.createElement(StateContext.Provider, { value: { snapshot: reminderSnapshot, connected: false, activity: null } }, React.createElement(TaskReminderWindow))));
    assert.equal(locks().at(-1), 0, '连接异常必须释放桌面');
    await React.act(async () => reminderRoot.render(React.createElement(StateContext.Provider, { value: { snapshot: reminderSnapshot, connected: true, activity: null } }, React.createElement(TaskReminderWindow))));
    assert.equal(locks().at(-1), 60, '恢复连接后继续未结束的休息');
    clock += 61_000;
    await React.act(async () => { await new Promise(resolve => setTimeout(resolve, 300)); });
    assert.equal(requests.filter(value => value.url.endsWith('/rest-run/confirm')).length, 1);
    assert.equal(plays, 2);
    assert.equal(locks().at(-1), 0, '倒计时结束即释放桌面');
  } finally {
    await React.act(async () => reminderRoot.unmount()); reminder.remove();
    localStorage.removeItem('task-reminder-rest:rest-run');
    globalThis.localStorage = previousStorage;
    Date.now = previousNow;
    globalThis.isTauri = false; delete window.__TAURI_INTERNALS__;
  }
});

test('普通提醒提交真实正文，原生失败不能回报已提交', async () => {
  const { deliverTaskNotification } = await server.ssrLoadModule('/src/services/taskDesktop.ts');
  const originalNotification = window.Notification;
  globalThis.isTauri = true;
  window.Notification = { permission: 'granted' };
  const calls = [];
  window.__TAURI_INTERNALS__ = { invoke: async (command, args) => { calls.push({ command, args }); } };
  try {
    const run = { title: '喝水', content: '起来喝一杯水', sound: false, confirmation: 'NONE' };
    assert.equal(await deliverTaskNotification(run), 'SUBMITTED');
    assert.deepEqual(calls, [{ command: 'send_task_notification', args: { title: '喝水', body: '起来喝一杯水', sound: false } }]);
    window.__TAURI_INTERNALS__.invoke = async () => { throw new Error('系统通知失败'); };
    await assert.rejects(deliverTaskNotification(run), /系统通知失败/);
  } finally {
    globalThis.isTauri = false; delete window.__TAURI_INTERNALS__; window.Notification = originalNotification;
  }
});

test('待确认提醒打开全局窗口且不重复提交系统通知', async () => {
  const { AutomationProvider } = await server.ssrLoadModule('/src/context/AutomationContext.tsx');
  const calls = [];
  const sources = [];
  const oldEventSource = globalThis.EventSource;
  globalThis.isTauri = true;
  window.__TAURI_INTERNALS__ = {
    invoke: async (command, args) => {
      calls.push({ command, args });
      if (command === 'task_notification_status') return { mode: 'DEV_FALLBACK', authorized: true, alertEnabled: true, soundEnabled: true };
      if (command === 'task_activity_status') return { supported: false, locked: false, idleSeconds: 0 };
      if (command.includes('claim')) return { code: 200, data: true };
      if (command === 'plugin:event|listen') return 1;
    },
    transformCallback: () => 1,
  };
  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = { unregisterListener() {} };
  globalThis.EventSource = class {
    constructor() { sources.push(this); this.listeners = new Map(); }
    addEventListener(name, callback) { this.listeners.set(name, callback); }
    close() {}
    emit(name, data) { this.listeners.get(name)?.({ data: JSON.stringify(data) }); }
  };
  const providerRoot = createRoot(document.createElement('div'));
  try {
    await React.act(async () => providerRoot.render(React.createElement(MessageProvider, null,
      React.createElement(AutomationProvider, null, React.createElement('span', null, 'ready')))));
    assert.equal(sources.length, 1);
    const run = { id: 'sedentary-run', title: '久坐提醒', content: '起来活动一下',
      desktopStatus: 'PENDING', confirmation: 'WAITING', sound: true };
    await React.act(async () => {
      sources[0].emit('snapshot', { tasks: [], pending: [run] });
      await new Promise(resolve => setTimeout(resolve, 10));
    });
    assert.ok(calls.some(call => call.command === 'sync_task_reminder' && call.args.visible === true));
    assert.ok(!calls.some(call => call.command === 'send_task_notification'));
  } finally {
    await React.act(async () => providerRoot.unmount());
    globalThis.EventSource = oldEventSource;
    globalThis.isTauri = false;
    delete window.__TAURI_INTERNALS__;
    delete window.__TAURI_EVENT_PLUGIN_INTERNALS__;
  }
});

test('提醒设置自动保存、失败回退；数字微调同步受控值并在失焦保存', async () => {
  const { TaskReminderSettings } = await server.ssrLoadModule('/src/components/task/TaskReminderSettings.tsx');
  const previousInternals = window.__TAURI_INTERNALS__;
  const previousTauri = globalThis.isTauri;
  let preferences = { restLock: false, soundId: 'builtin:bell', repeatSeconds: 4 };
  const saved = [];
  let fail = false;
  globalThis.isTauri = true;
  window.__TAURI_INTERNALS__ = { invoke: async (command, payload) => {
    if (command === 'task_reminder_configuration') return {
      preferences,
      sounds: [{ id: 'builtin:bell', name: '铃声', source: 'builtin' }, { id: 'builtin:soft', name: '柔和', source: 'builtin' }],
    };
    if (command === 'save_task_reminder_preferences') {
      if (fail) throw new Error('模拟写入失败');
      preferences = { ...payload.preferences };
      saved.push(preferences);
    }
  } };
  try {
    await React.act(async () => root.render(React.createElement(MessageProvider, null, React.createElement(TaskReminderSettings))));
    assert.doesNotMatch(container.textContent, /保存提醒设置/);
    assert.equal(saved.length, 0, '加载不应触发保存');
    await React.act(async () => container.querySelector('[role="switch"]').click());
    assert.equal(preferences.restLock, true);
    const select = container.querySelector('select');
    await React.act(async () => {
      select.value = 'builtin:soft';
      select.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
    });
    assert.equal(preferences.soundId, 'builtin:soft');
    const number = container.querySelector('input[type="number"]');
    await React.act(async () => number.focus());
    await React.act(async () => container.querySelector('[aria-label="增加"]').click());
    assert.equal(number.value, '5');
    await React.act(async () => number.blur());
    assert.equal(preferences.repeatSeconds, 5);
    assert.equal(preferences.restLock, true);
    fail = true;
    await React.act(async () => container.querySelector('[role="switch"]').click());
    assert.equal(container.querySelector('[role="switch"]').getAttribute('aria-checked'), 'true');
    assert.match(container.textContent, /自动保存失败/);
    const count = saved.length;
    await React.act(async () => {
      number.focus();
      Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(number, '0');
      number.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    });
    await React.act(async () => number.blur());
    assert.equal(saved.length, count);
    assert.match(container.textContent, /请输入 1 至 3600/);
  } finally {
    window.__TAURI_INTERNALS__ = previousInternals;
    globalThis.isTauri = previousTauri;
  }
});


test('任务同步异常使用全局 Message，同次异常不重复提示，恢复后更新提示', async () => {
  assert.equal(container.querySelector('[aria-label="任务运行条件"]'), null);
  await render(false);
  assert.doesNotMatch(container.textContent, /任务同步暂未就绪/);
  await React.act(async () => { await new Promise(resolve => setTimeout(resolve, 1550)); });
  assert.match(container.textContent, /任务同步暂未就绪/);
  assert.doesNotMatch(container.querySelector('main').textContent, /任务同步暂未就绪/);
  await click('关闭提示');
  await render(false);
  assert.doesNotMatch(container.textContent, /任务同步暂未就绪/);
  await render(true);
  assert.match(container.textContent, /任务同步已恢复/);
});

test('日报预览仅在点击后弹窗展示，返回编辑保留草稿，再打开使用最新草稿', async () => {
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), ...options });
    const spec = JSON.parse(options.body);
    return { ok: true, json: async () => ({ code: 200, data: { title: spec.title, content: '日报内容', html: '<h1>邮件排版</h1>', nextAt: null } }) };
  };
  await click('新建任务');
  const template = container.querySelector('select');
  await React.act(async () => {
    template.value = 'DAILY_REPORT';
    template.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
  });
  assert.equal(document.querySelector('iframe'), null);
  assert.equal(requests.length, 0);
  await click('预览（不发送）');
  const modal = document.querySelector('[aria-label="报告邮件预览"]');
  assert.ok(modal);
  assert.equal(modal.querySelector('iframe').getAttribute('sandbox'), '');
  assert.equal(modal.querySelector('iframe').getAttribute('srcdoc'), '<h1>邮件排版</h1>');
  assert.equal(container.querySelector('form iframe'), null);
  await click('返回编辑');
  assert.equal(document.querySelector('iframe'), null);
  assert.equal(document.getElementById('task-title').value, '每日日报');
  await input('task-title', '新的日报');
  await click('预览（不发送）');
  assert.equal(JSON.parse(requests.at(-1).body).title, '新的日报');
  assert.ok(requests.every(request => request.url.endsWith('/preview')));
});

test('预览失败可重试，加载期间关闭后迟到响应不会重新打开弹窗', async () => {
  await click('编辑', 0);
  globalThis.fetch = async () => { throw new Error('模拟预览失败'); };
  await click('预览（不发送）');
  assert.match(document.body.textContent, /模拟预览失败/);
  let finish;
  globalThis.fetch = () => new Promise(resolve => { finish = resolve; });
  await click('重新预览');
  assert.match(document.body.textContent, /正在生成预览/);
  await click('返回编辑');
  await React.act(async () => finish({ ok: true, json: async () => ({ code: 200, data: { title: '旧预览', content: '旧内容', nextAt: null, html: '' } }) }));
  assert.equal(document.querySelector('[aria-label="任务预览"]'), null);
  assert.equal(document.getElementById('task-title').value, '任务A');
});

test('预览先校验必填，关闭桌面通知同时清除声音和确认并阻止无渠道提交', async () => {
  await click('新建任务');
  await click('预览（不发送）');
  assert.equal(requests.length, 0);
  await input('task-title', '测试提醒');
  const checkbox = text => [...container.querySelectorAll('label')].find(label => label.textContent === text).querySelector('input');
  await React.act(async () => checkbox('需要手动确认').click());
  await React.act(async () => checkbox('桌面通知').click());
  for (const label of ['提醒声音', '需要手动确认']) {
    assert.equal(checkbox(label).checked, false);
    assert.equal(checkbox(label).disabled, true);
  }
  await click('预览（不发送）');
  assert.match(container.textContent, /至少选择一种通知方式/);
  assert.equal(requests.length, 0);
});
