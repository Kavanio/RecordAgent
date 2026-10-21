import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let dom, server, React, createRoot, Page, MessageProvider, root, container;
let info, calls, changed, saveError, restartError;
const token = 'test-only-token-'.repeat(3);
before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement', 'Event']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ BackendConnectionSettings: Page } = await server.ssrLoadModule('/src/components/settings/BackendConnectionSettings.tsx'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
});
after(async () => { await server?.close(); dom?.window.close(); });
beforeEach(() => {
  info = { mode: 'sidecar', remoteUrl: 'https://old.example.com', savedRemoteUrl: 'https://new.example.com', hasApiToken: true, restartRequired: true };
  calls = []; changed = true; saveError = null; restartError = null;
  window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
    calls.push({ command, args });
    if (command === 'get_backend_connection') return { ...info };
    if (command === 'get_backend_api_token') return token;
    if (command === 'save_remote_backend_url') {
      if (saveError) throw saveError;
      return changed;
    }
    if (command === 'restart_application' && restartError) throw restartError;
  } };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
});
afterEach(async () => { await React.act(async () => root.unmount()); container.remove(); });
const render = () => React.act(async () => root.render(React.createElement(MessageProvider, null, React.createElement(Page))));
const dialog = () => document.querySelector('[role="dialog"]');
async function click(text, scope = document) {
  const button = [...scope.querySelectorAll('button')].find(item => item.textContent === text);
  assert.ok(button, `找不到按钮：${text}`);
  await React.act(async () => button.click());
}
async function mode(value) {
  await React.act(async () => {
    const select = dialog().querySelector('select'); select.value = value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

test('默认仅显示运行状态和配置入口；弹窗读取已保存配置，取消不写入', async () => {
  await render();
  assert.equal(document.querySelector('input'), null);
  assert.match(container.textContent, /本机后端（自动降级）/);
  assert.match(container.textContent, /等待重启生效/);
  await click('配置连接');
  assert.equal(dialog().querySelector('#remote-backend-url').value, info.savedRemoteUrl);
  assert.equal(dialog().querySelector('#remote-backend-token').value, '');
  await click('取消');
  assert.equal(dialog(), null);
  assert.equal(calls.some(call => call.command === 'save_remote_backend_url'), false);
});

test('保存远程配置可沿用钥匙串 Token；仅确认后才调用原生重启，不热切换当前连接', async () => {
  await render(); await click('配置连接'); await click('保存配置');
  const save = calls.find(call => call.command === 'save_remote_backend_url');
  assert.deepEqual(save.args, { remoteUrl: info.savedRemoteUrl, apiToken: token });
  assert.match(container.textContent, /本机后端（自动降级）/);
  assert.match(dialog().textContent, /重启应用以切换后端/);
  assert.equal(calls.some(call => call.command === 'restart_application'), false);
  await click('立即重启', dialog());
  assert.equal(calls.filter(call => call.command === 'restart_application').length, 1);
  assert.equal(calls.some(call => call.command === 'start_local_backend_fallback'), false);
});

test('切回本机保存 null；稍后重启后仍有持久的待生效入口', async () => {
  info.mode = 'remote'; await render(); await click('配置连接'); await mode('local');
  assert.equal(dialog().querySelector('input'), null);
  await click('保存配置');
  assert.deepEqual(calls.find(call => call.command === 'save_remote_backend_url').args, { remoteUrl: null, apiToken: null });
  await click('稍后重启');
  assert.equal(dialog(), null);
  assert.match(container.textContent, /当前连接：https:\/\/old.example.com/);
  assert.match(container.textContent, /已保存：本机后端；等待重启生效/);
  assert.equal(calls.some(call => call.command === 'restart_application'), false);
});

test('配置未变不弹重启提示；保存失败保留草稿且不重启', async () => {
  info.restartRequired = false; changed = false;
  await render(); await click('配置连接'); await click('保存配置');
  assert.equal(dialog(), null);
  assert.match(document.body.textContent, /配置未变化，无需重启/);
  await click('配置连接'); saveError = '磁盘写入失败'; await click('保存配置');
  assert.match(dialog().textContent, /配置后端连接/);
  assert.match(document.body.textContent, /磁盘写入失败/);
  assert.equal(calls.some(call => call.command === 'restart_application'), false);
});

test('待重启时再次保存相同配置，不误报无需重启', async () => {
  changed = false;
  await render(); await click('配置连接'); await click('保存配置');
  assert.equal(dialog(), null);
  assert.match(document.body.textContent, /之前保存的配置仍需重启生效/);
  assert.match(container.textContent, /等待重启生效/);
});

test('自动重启失败有手动退出指引，并允许重试', async () => {
  await render(); await click('立即重启'); restartError = 'restart failed';
  await click('立即重启', dialog());
  assert.match(document.body.textContent, /自动重启失败，请退出应用后重新打开/);
  assert.equal(dialog().getAttribute('aria-busy'), 'false');
});
