import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, server, React, createRoot, Modal, Skeleton, LoadingOverlay, root, container;
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
});
after(async () => { await server?.close(); dom?.window.close(); });
beforeEach(() => { container = document.createElement('div'); document.body.append(container); root = createRoot(container); });
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });
const render = element => React.act(async () => root.render(element));

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
