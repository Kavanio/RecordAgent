import assert from 'node:assert/strict';
import { test, before, after } from 'node:test';
import { createServer } from 'vite';
let server, repeatReminderAudio, waitForReminder;
before(async () => {
  server = await createServer({ server: { middlewareMode: true }, appType: 'custom' });
  ({ repeatReminderAudio, waitForReminder } = await server.ssrLoadModule('/src/services/taskReminderAudio.ts'));
});
after(async () => { await server?.close(); });

test('长音频未结束前不开始间隔，间隔结束后才播放下一段', async () => {
  const controller = new AbortController();
  let endAudio, endWait, plays = 0;
  const events = [];
  const play = async () => {
    events.push('play'); plays++;
    if (plays === 2) { controller.abort(); return; }
    await new Promise(resolve => { endAudio = resolve; });
  };
  const wait = async milliseconds => {
    if (controller.signal.aborted) return;
    events.push(`wait:${milliseconds}`);
    await new Promise(resolve => { endWait = resolve; });
  };
  const loop = repeatReminderAudio('custom', 7, controller.signal, play, wait);
  await Promise.resolve(); assert.deepEqual(events, ['play']);
  endAudio(); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(events, ['play', 'wait:7000']);
  endWait(); await loop;
  assert.deepEqual(events, ['play', 'wait:7000', 'play']);
});

test('取消等待会立即停止循环，不触发下一次声音', async () => {
  const controller = new AbortController();
  let plays = 0;
  const loop = repeatReminderAudio('custom', 3600, controller.signal, async () => { plays++; });
  await Promise.resolve();
  controller.abort();
  await assert.rejects(loop, error => error.name === 'AbortError');
  assert.equal(plays, 1);
});

test('已经取消的播放不再启动；等待也拒绝已经取消的信号', async () => {
  const controller = new AbortController(); controller.abort();
  await repeatReminderAudio('custom', 1, controller.signal, async () => assert.fail('不应播放'));
  await assert.rejects(waitForReminder(1000, controller.signal), error => error.name === 'AbortError');
});
