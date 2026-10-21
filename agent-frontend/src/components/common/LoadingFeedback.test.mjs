import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';
import { readFile } from 'node:fs/promises';

let dom, server, React, createRoot, Modal, Skeleton, LoadingOverlay, LoadingTree, root, container;
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ Modal } = await server.ssrLoadModule('/src/components/common/Modal.tsx'));
  ({ Skeleton } = await server.ssrLoadModule('/src/components/common/Skeleton.tsx'));
  ({ LoadingOverlay } = await server.ssrLoadModule('/src/components/common/LoadingOverlay.tsx'));
  ({ LoadingTree } = await server.ssrLoadModule('/src/components/common/LoadingTree.tsx'));
});
after(async () => { await server?.close(); dom?.window.close(); });
beforeEach(() => { container = document.createElement('div'); document.body.append(container); root = createRoot(container); });
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });
const render = element => React.act(async () => root.render(element));

test('大树从根部沿单条闭合外缘成型，追光与底稿共用同一轮廓', async () => {
  await render(React.createElement(LoadingTree));
  const svg = container.querySelector('svg');
  const outline = svg.querySelector('[data-tree-part="outline"]');
  const trace = svg.querySelector('[data-tree-part="trace"]');
  assert.match(outline.getAttribute('d'), /^M28 102/);
  assert.equal((outline.getAttribute('d').match(/[Mm]/g) ?? []).length, 1, '外围必须一笔连续，不由多段同时出现');
  assert.match(outline.getAttribute('d'), /Z$/);
  assert.equal(trace.getAttribute('d'), outline.getAttribute('d'));
  assert.equal(outline.getAttribute('pathLength'), '1');
  assert.ok(svg.querySelector('[data-tree-part="stem"]'));
  assert.equal(svg.querySelector('[data-tree-part="branches"]').children.length, 4);
  assert.equal(svg.querySelector('[data-tree-part="leaves"]').children.length, 4);
  assert.equal(svg.getAttribute('aria-hidden'), 'true');
  assert.equal(svg.getAttribute('focusable'), 'false');
  assert.equal(container.querySelectorAll('[role="status"]').length, 1);
  assert.match(container.textContent, /正在加载/);
});

test('多个大树实例使用独立渐变 ID，重渲染不重建 SVG，尺寸和自定义文案保持兼容', async () => {
  const trees = label => React.createElement(React.Fragment, null,
    React.createElement(LoadingTree, { size: 'large', label, className: 'custom-tree' }),
    React.createElement(LoadingTree, { size: 'small', label: '' }));
  await render(trees('正在读取资料…'));
  const svgs = [...container.querySelectorAll('svg')];
  const ids = [...container.querySelectorAll('defs [id]')].map(item => item.id);
  assert.equal(new Set(ids).size, ids.length);
  assert.equal(ids.length, 4);
  for (const svg of svgs) {
    const ownIds = new Set([...svg.querySelectorAll('defs [id]')].map(item => item.id));
    for (const item of svg.querySelectorAll('[fill^="url"], [stroke^="url"]')) {
      const reference = item.getAttribute('fill')?.startsWith('url') ? item.getAttribute('fill') : item.getAttribute('stroke');
      assert.ok(ownIds.has(reference.slice(5, -1)), '每个渐变引用必须来自自身实例');
    }
  }
  assert.ok(container.firstElementChild.classList.contains('custom-tree'));
  assert.equal(container.lastElementChild.textContent, '');
  await render(trees('正在更新资料…'));
  assert.equal(container.querySelector('svg'), svgs[0]);
  assert.deepEqual([...container.querySelectorAll('defs [id]')].map(item => item.id), ids);
  assert.match(container.firstElementChild.textContent, /正在更新资料/);
});

test('描边阶段统一周期，先画外缘、保留成型时段；减少动态效果时完整静态展示', async () => {
  const style = document.createElement('style');
  style.textContent = await readFile(new URL('./LoadingTree.module.css', import.meta.url), 'utf8');
  document.head.append(style);
  try {
    const rules = [...style.sheet.cssRules];
    assert.equal(rules.find(rule => rule.selectorText === '.root').style.getPropertyValue('--tree-cycle'), '6.4s');
    for (const selector of ['.outline', '.trace']) {
      assert.match(rules.find(rule => rule.selectorText === selector).style.getPropertyValue('animation'), /linear/);
    }
    const outline = rules.find(rule => rule.name === 'outlineDraw');
    assert.equal(outline.cssRules[0].style.getPropertyValue('stroke-dashoffset'), '1');
    assert.match(outline.cssRules[1].keyText, /28%/);
    assert.equal(outline.cssRules[1].style.getPropertyValue('stroke-dashoffset'), '0');
    const settle = rules.find(rule => rule.name === 'settle');
    assert.match(settle.cssRules[1].keyText, /82%/);
    const reduced = rules.find(rule => rule.conditionText === '(prefers-reduced-motion: reduce)');
    assert.ok(reduced);
    const stops = [...reduced.cssRules].find(rule => rule.style.getPropertyValue('animation') === 'none');
    for (const selector of ['.growth', '.draw', '.trace', '.wash', '.leaf', '.aura']) assert.ok(stops.selectorText.includes(selector));
    const drawn = [...reduced.cssRules].find(rule => rule.style.getPropertyValue('stroke-dashoffset') === '0');
    assert.equal(drawn.style.getPropertyValue('opacity'), '1');
    assert.equal([...reduced.cssRules].find(rule => rule.selectorText === '.trace').style.getPropertyValue('display'), 'none');
    for (const rule of rules.filter(rule => rule.style?.getPropertyValue('animation'))) {
      assert.match(rule.style.getPropertyValue('animation'), /var\(--tree-cycle\)/);
    }
  } finally { style.remove(); }
});

test('骨架有单一中文状态语义，装饰模式不生成重复播报', async () => {
  await render(React.createElement(Skeleton, { variant: 'metrics', label: '正在读取统计…' }));
  assert.equal(container.querySelector('[role="status"]').getAttribute('aria-label'), '正在读取统计…');
  assert.equal(container.firstElementChild.children.length, 4);
  await render(React.createElement(Skeleton, { variant: 'text', decorative: true }));
  assert.equal(container.querySelector('[role="status"]'), null);
  assert.equal(container.firstElementChild.getAttribute('aria-hidden'), 'true');
});

test('局部等待层可在完成后归还焦点，非活动状态完全移除', async () => {
  const button = document.createElement('button'); document.body.append(button); button.focus();
  await render(React.createElement(LoadingOverlay, { active: true, label: '正在同步…' }));
  assert.equal(document.activeElement, container.firstElementChild);
  assert.match(container.querySelector('[role="status"]').textContent, /正在同步/);
  await render(React.createElement(LoadingOverlay, { active: false }));
  assert.equal(container.children.length, 0);
  assert.equal(document.activeElement, button);
  button.remove();
});

test('Modal 默认仍可关闭，忙碌时屏蔽 Escape/遮罩且 Tab 不逃逸，完成后恢复关闭', async () => {
  let closed = 0;
  const modal = busy => React.createElement(Modal, { open: true, title: '测试表单', busy, onClose: () => closed++ },
    React.createElement('input', { 'aria-label': '名称' }));
  await render(modal(true));
  const panel = document.querySelector('[role="dialog"]');
  assert.ok(panel.querySelector('input').closest('[inert]'));
  await React.act(async () => {
    window.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }));
    panel.parentElement.dispatchEvent(new window.MouseEvent('mousedown', { bubbles: true }));
  });
  assert.equal(closed, 0);
  const tab = new window.KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
  await React.act(async () => window.dispatchEvent(tab));
  assert.equal(tab.defaultPrevented, true);
  assert.equal(document.activeElement, panel);
  await render(modal(false));
  assert.equal(panel.querySelector('input').closest('[inert]'), null);
  await React.act(async () => window.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' })));
  assert.equal(closed, 1);
});
