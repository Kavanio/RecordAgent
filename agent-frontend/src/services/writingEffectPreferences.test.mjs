import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { deferred, loadModule, resetModules, startHarness, stopHarness } from '../testing/harness.mjs';

let preferences;
const key = 'butvan.writingEffects';
const image = id => ({ id, name: `${id}.png`, dataUrl: 'data:image/png;base64,iVBORw0KGgo=' });
before(startHarness);
after(stopHarness);
beforeEach(async () => {
  localStorage.clear();
  delete window.__TAURI_INTERNALS__;
  resetModules();
  preferences = await loadModule('/src/services/writingEffectPreferences.ts');
});
afterEach(() => { delete globalThis.isTauri; delete window.__TAURI_INTERNALS__; });

test('默认采用鲜明混合图案，损坏和空配置可安全回退', () => {
  assert.equal(preferences.getWritingEffectPreferences().style, 'mixed');
  assert.equal(preferences.getWritingEffectPreferences().enabled, true);
  assert.equal(preferences.getWritingEffectPreferences().emojis.length, 12);
  localStorage.setItem(key, 'null');
  assert.equal(preferences.getWritingEffectPreferences().style, 'mixed');
  localStorage.setItem(key, '{broken');
  assert.equal(preferences.getWritingEffectPreferences().enabled, true);
});

test('保存后广播最新配置，连续修改不会覆盖图片；重读可恢复且删除最后图片退出图片模式', async () => {
  let signals = 0;
  const stop = preferences.subscribeWritingEffectPreferences(() => signals++);
  await Promise.all([
    preferences.updateWritingEffectPreferences({ images: [image('one')], style: 'images' }),
    preferences.updateWritingEffectPreferences({ emojis: ['🌸'], enabled: false }),
  ]);
  assert.equal(signals, 2);
  stop();
  resetModules();
  preferences = await loadModule('/src/services/writingEffectPreferences.ts');
  const restored = preferences.getWritingEffectPreferences();
  assert.equal(restored.images.length, 1);
  assert.equal(restored.enabled, false);
  assert.deepEqual(restored.emojis, ['🌸']);
  assert.equal(restored.style, 'images');
  await preferences.updateWritingEffectPreferences({ images: [] });
  assert.equal(preferences.getWritingEffectPreferences().style, 'mixed');
});

test('配置丢弃远程链接、非 PNG 内联图、过大图片和超额图片，值始终低于原生偏好上限', async () => {
  localStorage.setItem(key, JSON.stringify({ images: [
    { ...image('remote'), dataUrl: 'https://example.com/private.png' },
    { ...image('svg'), dataUrl: 'data:image/svg+xml;base64,AAAA' },
    { ...image('big'), dataUrl: `data:image/png;base64,${'A'.repeat(20000)}` },
    ...Array.from({ length: 10 }, (_, i) => image(String(i))),
  ] }));
  assert.equal(preferences.getWritingEffectPreferences().images.length, 8);
  await preferences.updateWritingEffectPreferences({});
  assert.ok(new TextEncoder().encode(localStorage.getItem(key)).length < 256 * 1024);
});

test('桌面端等待文件写入成功再发布，保存失败保留旧配置并允许重试', async () => {
  globalThis.isTauri = true;
  const pending = deferred();
  let signals = 0;
  const stop = preferences.subscribeWritingEffectPreferences(() => signals++);
  window.__TAURI_INTERNALS__ = { invoke: () => pending.promise };
  const saving = preferences.updateWritingEffectPreferences({ enabled: false });
  await Promise.resolve();
  assert.equal(signals, 0);
  assert.equal(preferences.getWritingEffectPreferences().enabled, true);
  pending.reject(new Error('磁盘不可写'));
  await assert.rejects(saving, /磁盘不可写/);
  assert.equal(signals, 0);
  window.__TAURI_INTERNALS__.invoke = async command => { assert.equal(command, 'save_desktop_preference'); };
  await preferences.updateWritingEffectPreferences({ enabled: false });
  assert.equal(signals, 1);
  assert.equal(preferences.getWritingEffectPreferences().enabled, false);
  stop();
  delete window.__TAURI_INTERNALS__;
});
