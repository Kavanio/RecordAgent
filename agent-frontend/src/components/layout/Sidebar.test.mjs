import assert from 'node:assert/strict';
import { test, before, after, beforeEach, afterEach } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let server, dom, React, createRoot, Sidebar, MessageProvider;
let root, container, removed, removeResult;
const originalFetch = globalThis.fetch;
const project = {
  id: 'project-1',
  name: 'Butvan Blog2.0',
  path: '/Users/test/Old Location/Butvan Blog2.0',
  createdAt: 1,
  availability: 'MISSING',
};

before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'Event', 'MouseEvent', 'localStorage']) {
    globalThis[key] = dom.window[key];
  }
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ Sidebar } = await server.ssrLoadModule('/src/components/layout/Sidebar.tsx'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
});

after(async () => {
  await server?.close();
  dom?.window.close();
  globalThis.fetch = originalFetch;
});

beforeEach(async () => {
  localStorage.clear();
  removed = [];
  removeResult = { success: true };
  globalThis.fetch = async () => ({
    ok: true,
    json: async () => ({ code: 200, data: { bound: false, maskedEmail: null, emailNotificationsEnabled: false, avatarVersion: null } }),
  });
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  await React.act(async () => root.render(React.createElement(MessageProvider, null,
    React.createElement(Sidebar, {
      activeFeature: 'chat',
      onSelectFeature() {},
      projects: [project],
      sessions: [],
      activeSessionId: '',
      onSelectSession() {},
      onNewGeneralChat() {},
      onNewProjectChat() {},
      onImportProject: async () => ({ success: true }),
      onRemoveProject: async (projectId) => { removed.push(projectId); return removeResult; },
      onDeleteSession: async () => ({ success: true }),
      onOpenSettings() {},
      onOpenAccountSettings() {},
    }))));
});

afterEach(async () => {
  await React.act(async () => root?.unmount());
  container?.remove();
});

async function click(element) {
  assert.ok(element);
  await React.act(async () => element.click());
}

function buttonByText(text) {
  return [...document.querySelectorAll('button')].find((button) => button.textContent?.trim() === text);
}

test('目录移动后的项目明确提示状态，并可通过项目菜单移除登记', async () => {
  assert.ok(document.querySelector('[aria-label="项目目录已移动或不可访问"]'));
  assert.ok(document.querySelector('[title*="可移除后重新导入"]'));
  assert.equal(document.querySelector('[aria-label="项目目录不可访问，无法新建会话"]')?.disabled, true);

  await click(document.querySelector('[aria-label="Butvan Blog2.0 项目操作"]'));
  await click(buttonByText('移除项目'));
  assert.match(document.body.textContent, /不会删除磁盘文件/);
  assert.match(document.body.textContent, /关联会话会保留为普通会话/);

  await click(buttonByText('移除项目'));
  assert.deepEqual(removed, [project.id]);
});

test('移除失败时保留确认弹框并展示全局错误', async () => {
  removeResult = { success: false, message: '项目目录册写入失败' };
  await click(document.querySelector('[aria-label="Butvan Blog2.0 项目操作"]'));
  await click(buttonByText('移除项目'));
  await click(buttonByText('移除项目'));

  assert.deepEqual(removed, [project.id]);
  assert.match(document.body.textContent, /项目目录册写入失败/);
  assert.match(document.body.textContent, /移除项目？/);
});
