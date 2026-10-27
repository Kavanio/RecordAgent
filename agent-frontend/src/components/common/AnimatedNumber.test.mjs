import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, loadModule, mount, startHarness, stopHarness } from '../../testing/harness.mjs';

let React, AnimatedNumber, handle, container, frames, media, listeners;
before(async () => {
  await startHarness();
  React = getReact();
  ({ AnimatedNumber } = await loadModule('/src/components/common/AnimatedNumber.tsx'));
});
after(async () => { await stopHarness(); });
beforeEach(async () => {
  frames = new Map(); listeners = new Set(); let sequence = 0;
  window.requestAnimationFrame = callback => { const id = ++sequence; frames.set(id, callback); return id; };
  window.cancelAnimationFrame = id => frames.delete(id);
  media = { matches: false, addEventListener: (_, callback) => listeners.add(callback), removeEventListener: (_, callback) => listeners.delete(callback) };
  window.matchMedia = () => media;
  handle = await mount();
  container = handle.container;
});
afterEach(async () => { await handle.unmount(); });
const render = async props => handle.render(React.createElement(AnimatedNumber, props));
const visible = () => container.querySelector('[aria-hidden="true"]').textContent;
async function advance(time) {
  const callbacks = [...frames.values()]; frames.clear();
  await act(async () => callbacks.forEach(callback => callback(time)));
}

test('等待数据就绪后才播放，600ms 内递增到真实值，读屏不播报中间值', async () => {
  await render({ value: 100, ready: false });
  assert.equal(visible(), '—'); assert.equal(frames.size, 0);
  await render({ value: 100, ready: true });
  assert.equal(visible(), '0');
  assert.equal(container.lastElementChild.lastElementChild.textContent, '100');
  await advance(0); await advance(300);
  assert.ok(Number(visible()) > 0 && Number(visible()) < 100);
  await advance(600);
  assert.equal(visible(), '100'); assert.equal(frames.size, 0);
  await render({ value: 120 });
  assert.equal(visible(), '120'); assert.equal(frames.size, 0, '实时更新不能重播入场动画');
});

test('动画过程中数据更新不回到零，格式化函数保持单位', async () => {
  const format = value => `${value} 分钟`;
  await render({ value: 100, format });
  await advance(0); await advance(300);
  await render({ value: 110, format });
  assert.notEqual(visible(), '0 分钟');
  await advance(600);
  assert.equal(visible(), '110 分钟');
});

test('金额动画保留两位小数，完成和后续更新不丢失分、零值或负金额', async () => {
  const format = value => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
  await render({ value: 12345.67, fractionDigits: 2, format });
  await advance(0); await advance(300);
  assert.equal(visible(), format(10802.46));
  await advance(600);
  assert.equal(visible(), format(12345.67));
  for (const value of [0.01, 0, -25.37]) {
    await render({ value, fractionDigits: 2, format });
    assert.equal(visible(), format(value));
    assert.equal(frames.size, 0);
  }
});

test('减少动态效果从一开始生效，也能立即终止正在播放的动画', async () => {
  media.matches = true;
  await render({ value: 80 });
  assert.equal(visible(), '80'); assert.equal(frames.size, 0);
  await handle.unmount();
  handle = await mount(); container = handle.container; media.matches = false;
  await render({ value: 90 });
  assert.equal(frames.size, 1);
  media.matches = true;
  await act(async () => listeners.forEach(callback => callback()));
  assert.equal(visible(), '90'); assert.equal(frames.size, 0);
});

test('严格模式下可正常完成；卸载清理帧回调和偏好监听', async () => {
  await handle.render(React.createElement(React.StrictMode, null, React.createElement(AnimatedNumber, { value: 60 })));
  assert.equal(frames.size, 1); assert.equal(listeners.size, 1);
  await advance(0); await advance(600);
  assert.equal(visible(), '60');
  await handle.unmount();
  assert.equal(frames.size, 0); assert.equal(listeners.size, 0);
  handle = await mount(); container = handle.container;
  await render({ value: 40 });
  await handle.unmount();
  assert.equal(frames.size, 0); assert.equal(listeners.size, 0);
  handle = await mount(); container = handle.container;
});
