import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, jsonResponse, loadModule, mount, startHarness, stopHarness, useFetchStub } from '../../../testing/harness.mjs';

let React, GoalBoard, MessageProvider, stub, view, goals, writes;
before(async () => {
  await startHarness();
  React = getReact();
  ({ GoalBoard } = await loadModule('/src/components/review/status/GoalBoard.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});
after(stopHarness);

beforeEach(() => {
  writes = [];
  goals = [
    { id: 'g1', title: '每周读书三次', constraintText: '不熬夜', successCriterion: '完成三次', state: 'ACTIVE', revision: 1, createdAt: '2026-11-01', updatedAt: '2026-11-01' },
    { id: 'g-old', title: '早睡打卡', constraintText: '23点前', successCriterion: '五天达标', state: 'ARCHIVED', revision: 2, createdAt: '2026-10-01', updatedAt: '2026-10-15' },
  ];
  stub = useFetchStub((url, init) => {
    const method = init?.method ?? 'GET';
    if (url.pathname.startsWith('/agent/reviews/goals')) {
      if (method === 'PUT' || method === 'POST') {
        const body = JSON.parse(init.body);
        writes.push({ path: url.pathname, method, body });
        const id = url.pathname.split('/').pop();
        const existing = goals.find(g => g.id === id);
        if (existing) {
          Object.assign(existing, { ...body, revision: existing.revision + 1 });
        } else {
          goals.push({ id: `g-${goals.length + 1}`, ...body, revision: 1, createdAt: '2026-11-14', updatedAt: '2026-11-14' });
        }
        return jsonResponse(existing ?? goals.at(-1));
      }
      return jsonResponse(goals);
    }
    return jsonResponse(null, 404);
  });
});

afterEach(async () => {
  await view?.unmount();
  view = null;
  stub.restore();
});

async function render() {
  view = await mount(React.createElement(MessageProvider, null, React.createElement(GoalBoard)));
}

test('支持编辑进行中的目标并带上 expectedRevision', async () => {
  await render();
  assert.match(document.body.textContent, /1\/3/);
  const editBtn = document.body.querySelector('button[aria-label="编辑目标 每周读书三次"]');
  assert.ok(editBtn);
  await act(async () => editBtn.click());

  const titleInput = document.body.querySelector('input[aria-label="目标名称"]');
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(titleInput, '每周读书四次');
    titleInput.dispatchEvent(new window.Event('input', { bubbles: true }));
    titleInput.dispatchEvent(new window.Event('change', { bubbles: true }));
  });
  const saveBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent === '保存修改');
  await act(async () => saveBtn.click());

  assert.equal(writes.length, 1);
  assert.equal(writes[0].body.expectedRevision, 1);
  assert.equal(writes[0].body.title, '每周读书四次');
  assert.equal(writes[0].body.state, 'ACTIVE');
  assert.match(document.body.textContent, /每周读书四次/);
});

test('支持展开已归档目标并恢复；满 3 个有效目标时禁用新增与恢复', async () => {
  await render();
  const toggle = document.body.querySelector('button[aria-expanded="false"]');
  assert.ok(toggle);
  await act(async () => toggle.click());
  assert.match(document.body.textContent, /早睡打卡/);

  const restoreBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent.includes('恢复'));
  await act(async () => restoreBtn.click());
  assert.equal(writes.length, 1);
  assert.equal(writes[0].body.state, 'ACTIVE');
  assert.equal(writes[0].body.expectedRevision, 2);
  assert.match(document.body.textContent, /2\/3/);

  goals.push({ id: 'g3', title: '第三项目标', constraintText: '', successCriterion: '', state: 'ACTIVE', revision: 1, createdAt: '', updatedAt: '' });
  await view.unmount();
  await render();
  assert.match(document.body.textContent, /3\/3/);
  const addBtn = [...document.body.querySelectorAll('button')].find(b => b.textContent.includes('新增目标'));
  assert.equal(addBtn.disabled, true);
});
