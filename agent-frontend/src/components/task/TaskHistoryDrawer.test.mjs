import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount } from '../../testing/harness.mjs';

let React, TaskHistoryDrawer, newAutomation, view;

before(async () => {
  ({ react: React } = await startHarness());
  ({ TaskHistoryDrawer } = await loadModule('/src/components/task/TaskHistoryDrawer.tsx'));
  ({ newAutomation } = await loadModule('/src/features/automation/presentation.ts'));
});

after(stopHarness);
afterEach(async () => {
  await view?.unmount();
  view = null;
});

test('执行历史抽屉加载时展示统一 LoadingTree 动画，不使用灰色圆环 spinner', async () => {
  const task = {
    id: 'task-1',
    version: 1,
    status: 'ENABLED',
    nextAt: null,
    activeSeconds: 0,
    spec: { ...newAutomation(), title: '每日汇报', content: '内容' },
  };
  view = await mount(React.createElement(TaskHistoryDrawer, {
    task,
    runs: [],
    loading: true,
    error: '',
    busy: false,
    onClose() {},
    onReload() {},
    onRetryEmail() {},
  }));
  const drawer = document.querySelector('[role="dialog"]');
  const status = drawer.querySelector('[role="status"]');
  assert.ok(status);
  assert.match(status.textContent, /正在同步执行记录/);
  assert.ok(status.querySelector('svg [data-tree-part="outline"]'));
  assert.ok(status.querySelector('svg [data-tree-part="leaves"]'));
});
