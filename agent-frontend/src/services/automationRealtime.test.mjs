import assert from 'node:assert/strict';
import { before, after, beforeEach, afterEach, test } from 'node:test';
import { createServer } from 'vite';

let server, subscribeAutomations, subscription, sources, states, snapshots;
const original = { EventSource: globalThis.EventSource, window: globalThis.window, document: globalThis.document, navigator: globalThis.navigator };
before(async () => {
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ subscribeAutomations } = await server.ssrLoadModule('/src/services/automationApi.ts'));
});
after(async () => { await server.close(); for (const [key, value] of Object.entries(original)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value }); });
beforeEach(context => {
  context.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 0 });
  sources = []; states = []; snapshots = [];
  globalThis.window = new EventTarget();
  globalThis.document = Object.assign(new EventTarget(), { visibilityState: 'visible' });
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: true } });
  globalThis.EventSource = class {
    constructor() { this.listeners = new Map(); this.readyState = 0; sources.push(this); }
    addEventListener(name, receive) { this.listeners.set(name, receive); }
    emit(name, value) { this.listeners.get(name)?.({ data: JSON.stringify(value) }); }
    close() { this.readyState = 2; }
  };
  subscription = subscribeAutomations(value => snapshots.push(value), state => states.push(state));
});
afterEach(() => { if (typeof subscription === 'function') subscription(); else subscription.close(); });

test('SSE彻底关闭后自行重建，收到完整快照前不宣称恢复', context => {
  sources[0].emit('snapshot', { tasks: [], pending: [] });
  sources[0].readyState = 2;
  sources[0].onerror();
  context.mock.timers.tick(2000);
  assert.equal(sources.length, 2);
  assert.notEqual(states.at(-1), 'LIVE');
  sources[1].emit('snapshot', { tasks: [], pending: [] });
  assert.equal(states.at(-1), 'LIVE');
});

test('心跳维持静默任务连接，丢失心跳后看门狗主动重连', context => {
  sources[0].emit('snapshot', { tasks: [], pending: [] });
  for (let i = 0; i < 9; i++) {
    context.mock.timers.tick(15_000);
    sources[0].emit('heartbeat', {});
  }
  assert.equal(sources.length, 1);
  assert.equal(states.at(-1), 'LIVE');
  context.mock.timers.tick(45_000);
  assert.equal(sources[0].readyState, 2);
  context.mock.timers.tick(2000);
  assert.equal(sources.length, 2);
});

test('网络恢复立即重连，健康连接的focus不产生重复订阅', () => {
  sources[0].emit('snapshot', { tasks: [], pending: [] });
  window.dispatchEvent(new Event('focus'));
  assert.equal(sources.length, 1);
  navigator.onLine = false;
  window.dispatchEvent(new Event('offline'));
  assert.equal(states.at(-1), 'OFFLINE');
  navigator.onLine = true;
  window.dispatchEvent(new Event('online'));
  assert.equal(sources.length, 2);
  sources[1].emit('heartbeat', {});
  assert.notEqual(states.at(-1), 'LIVE', '心跳不能冒充完整快照');
  sources[1].emit('snapshot', { tasks: [], pending: [] });
  assert.equal(states.at(-1), 'LIVE');
});

test('唤醒后重新建立过期连接，忽略旧连接的迟到快照', context => {
  sources[0].emit('snapshot', { tasks: [], pending: [] });
  context.mock.timers.tick(31_000);
  document.dispatchEvent(new Event('visibilitychange'));
  assert.equal(sources.length, 2);
  const count = snapshots.length;
  sources[0].emit('snapshot', { tasks: [{ id: 'stale' }], pending: [] });
  assert.equal(snapshots.length, count);
  sources[0].onerror();
  context.mock.timers.tick(2000);
  assert.equal(sources.length, 2, '旧连接不能启动另一轮重试');
});

test('手动重试替换旧订阅，卸载后清理定时器及所有恢复监听', context => {
  subscription.reconnect();
  assert.equal(sources.length, 2);
  assert.equal(sources[0].readyState, 2);
  sources[1].onerror();
  subscription.close();
  const count = states.length;
  context.mock.timers.tick(120_000);
  window.dispatchEvent(new Event('online'));
  window.dispatchEvent(new Event('focus'));
  document.dispatchEvent(new Event('visibilitychange'));
  sources[1].emit('snapshot', { tasks: [], pending: [] });
  assert.equal(sources.length, 2);
  assert.equal(states.length, count);
});

test('坏快照不会覆盖已有数据，重复失败退避重试而非密集建连', context => {
  sources[0].emit('snapshot', { tasks: [], pending: [] });
  sources[0].emit('snapshot', { tasks: null, pending: [] });
  context.mock.timers.tick(2000);
  sources[1].onerror();
  context.mock.timers.tick(3000);
  sources[2].onerror();
  assert.equal(states.at(-1), 'OFFLINE');
  context.mock.timers.tick(3000);
  assert.equal(sources.length, 3);
  context.mock.timers.tick(2000);
  assert.equal(sources.length, 4);
  assert.equal(snapshots.length, 1);
});
