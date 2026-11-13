import assert from 'node:assert/strict';
import { test, before, after, beforeEach, afterEach } from 'node:test';
import { startHarness, stopHarness, loadModule, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';

let React, Sidebar, MessageProvider, view, stub;
let removed, removeResult;
const project = {
  id: 'project-1',
  name: 'Butvan Blog2.0',
  path: '/Users/test/Old Location/Butvan Blog2.0',
  createdAt: 1,
  availability: 'MISSING',
};

before(async () => {
  ({ react: React } = await startHarness());
  ({ Sidebar } = await loadModule('/src/components/layout/Sidebar.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});

after(stopHarness);

beforeEach(async () => {
  localStorage.clear();
  removed = [];
  removeResult = { success: true };
  stub = useFetchStub(async (url) => {
    if (url.pathname.endsWith('/feishu')) return jsonResponse([]);
    return jsonResponse({ bound: false, maskedEmail: null, emailNotificationsEnabled: false, avatarVersion: null });
  });
  view = await mount(React.createElement(
    MessageProvider,
    null,
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
    }),
  ));
});

afterEach(async () => {
  await view?.unmount();
  stub?.restore();
});

async function click(element) {
  assert.ok(element);
  await act(async () => element.click());
}

function toggleButtonByLabel(label) {
  return view.queryAll('button[aria-expanded]').find((button) => button.textContent?.includes(label));
}

function buttonByText(text) {
  return [...document.querySelectorAll('button')].find((button) => button.textContent?.trim() === text);
}

test('项目与飞书对话分组默认收起，仅展示客户端对话区域', () => {
  const projectsToggle = toggleButtonByLabel('项目');
  const feishuToggle = toggleButtonByLabel('飞书对话');
  assert.equal(projectsToggle?.getAttribute('aria-expanded'), 'false');
  assert.equal(feishuToggle?.getAttribute('aria-expanded'), 'false');
  assert.doesNotMatch(view.text(), /Butvan Blog2\.0/);
  assert.doesNotMatch(view.text(), /暂无飞书对话/);
  assert.match(view.text(), /客户端对话/);
});

test('目录移动后的项目明确提示状态，并可通过项目菜单移除登记', async () => {
  await click(toggleButtonByLabel('项目'));
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
  await click(toggleButtonByLabel('项目'));
  await click(document.querySelector('[aria-label="Butvan Blog2.0 项目操作"]'));
  await click(buttonByText('移除项目'));
  await click(buttonByText('移除项目'));

  assert.deepEqual(removed, [project.id]);
  assert.match(document.body.textContent, /项目目录册写入失败/);
  assert.match(document.body.textContent, /移除项目？/);
});
