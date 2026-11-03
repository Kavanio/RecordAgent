import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import {
  act, deferred, getReact, jsonResponse, loadModule, mount,
  resetModules, startHarness, stopHarness, useFetchStub,
} from '../../testing/harness.mjs';

let React, CalendarStickyNotes, MessageProvider, view, fetchStub, respond;
let todos, deletes, patches, changed, refresh;
const focusDate = new Date(2026, 10, 3);
const weekly = { id: 'weekly-1', title: '整理本周资料', version: 7, recurrence: 'weekly', occurrenceDate: '2026-11-02', completed: false };
const monthly = { id: 'monthly-1', title: '本月读书', version: 3, recurrence: 'monthly', occurrenceDate: '2026-11-01', completed: true };

before(async () => {
  await startHarness({ globals: ['ResizeObserver', 'SVGElement'] });
  React = getReact();
});
after(stopHarness);
beforeEach(async () => {
  resetModules();
  window.localStorage.clear();
  todos = [{ ...weekly }, { ...monthly }];
  deletes = [];
  patches = [];
  changed = 0;
  refresh = async () => {};
  respond = (url, init) => {
    if (init?.method === 'DELETE') {
      deletes.push(url);
      todos = todos.filter(todo => !url.pathname.endsWith(`/${todo.id}`));
      return jsonResponse(null);
    }
    if (init?.method === 'PATCH') {
      patches.push(url);
      return jsonResponse({ ...weekly, eventType: 'todo', version: 8, details: { completed: true, recurrence: 'weekly' } });
    }
    if (url.pathname.endsWith('/recurring-todos')) return jsonResponse(todos);
    throw new Error(`未预期的请求：${url}`);
  };
  fetchStub = useFetchStub((url, init) => respond(url, init));
  ({ CalendarStickyNotes } = await loadModule('/src/components/calendar/CalendarStickyNotes.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
  const { resetCalendarStickyNoteExpansion } = await loadModule('/src/services/calendarStickyNoteState.ts');
  resetCalendarStickyNoteExpansion(true);
});
afterEach(async () => {
  await view?.unmount();
  view = null;
  fetchStub.restore();
});
const render = async () => {
  view = await mount(React.createElement(React.StrictMode, null,
    React.createElement(MessageProvider, null, React.createElement(CalendarStickyNotes, {
      focusDate, revision: 0, onChanged: async () => { changed += 1; await refresh(); },
    }))));
};
const button = label => [...document.querySelectorAll('button')]
  .find(element => (element.getAttribute('aria-label') || element.textContent) === label);
const click = async label => {
  const target = button(label);
  assert.ok(target, `找不到按钮：${label}`);
  await act(async () => target.click());
};
const deleteLabel = todo => `删除待办“${todo.title}”`;

test('便签删除需二次确认，取消后保留条目且不触发完成或删除请求', async () => {
  await render();
  const deleteButton = button(deleteLabel(weekly));
  assert.equal(deleteButton.parentElement.tagName, 'DIV');
  await click(deleteLabel(weekly));
  assert.match(document.querySelector('[role="dialog"]').textContent, /删除整个重复待办/);
  assert.equal(deletes.length, 0);
  assert.equal(patches.length, 0);
  await click('取消');
  assert.equal(document.querySelector('[role="dialog"]'), null);
  assert.ok(button(deleteLabel(weekly)));
  assert.equal(changed, 0);
});

for (const [todo, impact, other] of [[weekly, '以后每周都不会再出现', monthly], [monthly, '以后每个月都不会再出现', weekly]]) {
  test(`${todo.recurrence} 便签携带版本删除整个重复待办，刷新日历且保留另一张便签`, async () => {
    await render();
    await click(deleteLabel(todo));
    assert.match(document.querySelector('[role="dialog"]').textContent, new RegExp(impact));
    await click('删除整个重复待办');
    assert.equal(deletes.length, 1);
    assert.ok(deletes[0].pathname.endsWith(`/${todo.id}`));
    assert.equal(deletes[0].searchParams.get('expectedVersion'), String(todo.version));
    assert.equal(patches.length, 0);
    assert.equal(button(deleteLabel(todo)), undefined);
    assert.ok(button(deleteLabel(other)));
    assert.equal(view.queryAll('article').length, 1);
    assert.equal(document.querySelector('[role="dialog"]'), null);
    assert.equal(changed, 1);
    assert.match(document.body.textContent, /已删除整个重复待办/);
  });
}

test('删除中禁止重复提交、取消与勾选；版本冲突保留条目并允许取消后重试', async () => {
  const pending = deferred();
  const normal = respond;
  respond = (url, init) => {
    if (init?.method === 'DELETE') { deletes.push(url); return pending.promise; }
    return normal(url, init);
  };
  await render();
  await click(deleteLabel(weekly));
  await click('删除整个重复待办');
  assert.equal(document.querySelector('[role="dialog"]').getAttribute('aria-busy'), 'true');
  assert.ok(button(deleteLabel(weekly)).disabled);
  assert.ok(button(`标记完成：${weekly.title}`).disabled);
  await click('处理中…');
  await click('取消');
  assert.equal(deletes.length, 1);
  assert.ok(document.querySelector('[role="dialog"]'));
  await act(async () => pending.resolve({ ok: false, status: 409, json: async () => ({ code: 409, message: '待办已更新，请刷新后重试' }) }));
  assert.match(document.body.textContent, /待办已更新，请刷新后重试/);
  assert.ok(button(deleteLabel(weekly)));
  assert.equal(changed, 0);
  await click('取消');
  assert.equal(button(deleteLabel(weekly)).disabled, false);
  respond = normal;
  await click(deleteLabel(weekly));
  await click('删除整个重复待办');
  assert.equal(button(deleteLabel(weekly)), undefined);
});

test('已成功删除但日历刷新失败时明确提示，不恢复已删除条目', async () => {
  refresh = async () => { throw new Error('读取月历超时'); };
  await render();
  await click(deleteLabel(monthly));
  await click('删除整个重复待办');
  assert.match(document.body.textContent, /待办已删除，但日历刷新失败：读取月历超时/);
  assert.equal(button(deleteLabel(monthly)), undefined);
  assert.equal(deletes.length, 1);
});

test('勾选在途时不能删除同一条目，完成后使用更新的版本删除', async () => {
  const pending = deferred();
  const normal = respond;
  respond = (url, init) => {
    if (init?.method === 'PATCH') { patches.push(url); return pending.promise; }
    return normal(url, init);
  };
  await render();
  await click(`标记完成：${weekly.title}`);
  assert.ok(button(deleteLabel(weekly)).disabled);
  await click(deleteLabel(weekly));
  assert.equal(document.querySelector('[role="dialog"]'), null);
  await act(async () => pending.resolve(jsonResponse({ ...weekly, eventType: 'todo', version: 8, details: { completed: true, recurrence: 'weekly' } })));
  await click(deleteLabel(weekly));
  await click('删除整个重复待办');
  assert.equal(deletes[0].searchParams.get('expectedVersion'), '8');
  assert.equal(patches.length, 1);
});
