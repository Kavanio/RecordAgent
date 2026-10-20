import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, server, React, createRoot, StudyTimeline, root, container;
const dateKey = '2026-10-20';
const at = (day, hour) => new Date(`${day}T${hour}:00:00`).toISOString();
const session = (id, day = dateKey) => ({ id, content: `学习${id}`, category: '阅读',
  startedAt: at(day, '08'), endedAt: null, status: 'active', durationSeconds: 3600,
  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, source: 'project', version: 1 });
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  globalThis.window = dom.window; globalThis.document = dom.window.document;
  globalThis.getComputedStyle = () => ({ getPropertyValue: () => '132px' });
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' });
  ({ StudyTimeline } = await server.ssrLoadModule('/src/components/study/StudyTimeline.tsx'));
});
after(async () => { await server?.close(); dom?.window.close(); });
beforeEach(() => { container = document.createElement('div'); document.body.append(container); root = createRoot(container); });
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });
async function render(sessions, day = dateKey, now = new Date(`${day}T09:00:00`).getTime()) {
  await React.act(async () => root.render(React.createElement(StudyTimeline, { sessions, dateKey: day, now })));
}
const bars = () => [...container.querySelectorAll('[role="img"]')];

test('分行错峰 40ms 且最多延后 160ms，不改变实际时间比例', async () => {
  await render(Array.from({ length: 8 }, (_, index) => session(String(index))));
  assert.deepEqual(bars().map(bar => bar.parentElement.parentElement.style.getPropertyValue('--task-enter-delay')),
    ['0ms', '40ms', '80ms', '120ms', '160ms', '160ms', '160ms', '160ms']);
  assert.ok(Math.abs(Number.parseFloat(bars()[0].style.left) - 100 / 3) < 0.001);
  assert.ok(Math.abs(Number.parseFloat(bars()[0].style.width) - 100 / 24) < 0.001);
});

test('实时计时和同日期数据刷新复用条形节点，不重新播放入场', async () => {
  const records = [session('active')];
  await render(records);
  const bar = bars()[0], initialWidth = Number.parseFloat(bar.style.width);
  await render(records, dateKey, new Date(`${dateKey}T09:01:00`).getTime());
  assert.equal(bars()[0], bar);
  assert.ok(Number.parseFloat(bar.style.width) > initialWidth);
  await render([{ ...records[0], durationSeconds: 3660, version: 2 }]);
  assert.equal(bars()[0], bar);
});

test('切换日期即使记录 ID 相同，也重新建立入场节点', async () => {
  await render([session('same-id')]);
  const first = bars()[0];
  await render([session('same-id', '2026-10-21')], '2026-10-21');
  assert.notEqual(bars()[0], first);
  assert.match(bars()[0].getAttribute('aria-label'), /学习same-id/);
});

test('样式从左端展开并淡入，减少动态效果时取消入场及持续提示动画', async () => {
  // JSDOM 不执行 CSS 动画；这里校验动效声明，真实桌面视觉效果不在此测试中模拟。
  const css = await readFile(new URL('./StudyTimeline.module.css', import.meta.url), 'utf8');
  assert.match(css, /transform-origin: left center/);
  assert.match(css, /animation: revealTask 520ms/);
  assert.match(css, /from\s*\{\s*opacity: 0; transform: scaleX\(0\)/);
  assert.match(css, /@media \(prefers-reduced-motion: reduce\)\s*\{\s*\.task, \.activeTask::after \{ animation: none;/);
});
