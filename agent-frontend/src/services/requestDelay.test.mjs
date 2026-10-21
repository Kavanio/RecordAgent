import assert from 'node:assert/strict';
import { test } from 'node:test';
import { waitForRequestDelay } from './requestDelay.ts';

test('每个请求独立额外等待 1000ms，并行请求不串行累加', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  let completed = 0;
  const requests = Promise.all([1, 2, 3].map(() => waitForRequestDelay(1000).then(() => completed++)));
  t.mock.timers.tick(999);
  await Promise.resolve();
  assert.equal(completed, 0);
  t.mock.timers.tick(1);
  await requests;
  assert.equal(completed, 3);
});

test('等待期间取消立即退出，不继续执行发送步骤并清理监听', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const controller = new AbortController();
  const remove = t.mock.method(controller.signal, 'removeEventListener');
  let sent = false;
  const request = waitForRequestDelay(1000, controller.signal).then(() => { sent = true; });
  const reason = new DOMException('用户取消', 'AbortError');
  controller.abort(reason);
  await assert.rejects(request, error => error === reason);
  t.mock.timers.tick(1000);
  assert.equal(sent, false);
  assert.equal(remove.mock.callCount(), 1);
});

test('已取消的请求不等待；零延迟立即通过；正常结束清理取消监听', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const cancelled = AbortSignal.abort(new Error('已取消'));
  await assert.rejects(waitForRequestDelay(1000, cancelled), /已取消/);
  await waitForRequestDelay(0);
  const controller = new AbortController();
  const remove = t.mock.method(controller.signal, 'removeEventListener');
  const request = waitForRequestDelay(1000, controller.signal);
  t.mock.timers.tick(1000);
  await request;
  assert.equal(remove.mock.callCount(), 1);
});
