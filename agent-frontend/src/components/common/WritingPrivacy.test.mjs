import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, loadModule, mount, resetModules, startHarness, stopHarness } from '../../testing/harness.mjs';

let React, Page, Settings, Provider, preferences, handle, editor, body;
let originalTimeout, originalClearTimeout, idleTimers, now, nextTimer;
before(async () => {
  await startHarness(); React = getReact(); window.scrollBy = () => {};
  originalTimeout = window.setTimeout;
  originalClearTimeout = window.clearTimeout;
});
beforeEach(async () => {
  idleTimers = new Map(); now = 0; nextTimer = 0;
  window.setTimeout = (callback, delay, ...args) => {
    if (delay !== 1500) return originalTimeout.call(window, callback, delay, ...args);
    const id = --nextTimer;
    idleTimers.set(id, { callback, due: now + delay });
    return id;
  };
  window.clearTimeout = id => {
    if (!idleTimers.delete(id)) originalClearTimeout.call(window, id);
  };
  localStorage.clear(); resetModules();
  ({ WritingEditorPage: Page } = await loadModule('/src/components/common/WritingEditorPage.tsx'));
  ({ WritingPrivacySettings: Settings } = await loadModule('/src/components/settings/WritingPrivacySettings.tsx'));
  ({ MessageProvider: Provider } = await loadModule('/src/components/common/Message.tsx'));
  preferences = await loadModule('/src/services/writingPrivacyPreferences.ts');
});
afterEach(async () => {
  await handle?.unmount(); handle = null;
  assert.equal(idleTimers.size, 0, '卸载时取消全部自动隐藏计时');
  window.setTimeout = originalTimeout;
  window.clearTimeout = originalClearTimeout;
  await act(async () => { await new Promise(resolve => window.requestAnimationFrame(resolve)); });
});
after(stopHarness);

async function render(props = {}) {
  handle = await mount(React.createElement(Provider, null, React.createElement(Page, {
    backLabel: '资料', identity: '新增资料', detail: '2026-11-03', privacyAvailable: true,
    initialTitle: '私人文章', initialBody: '## 私人标题\n\n第一段正文\n\n第二段正文\n\n- 列表一\n- 列表二\n\n> 引用正文\n\n```js\nconst secret = 1;\n```',
    onBack() {}, onSave() {}, ...props,
  })));
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 15)); });
  body = handle.query('[aria-label="正文"]'); editor = body.editor;
  // jsdom 不提供文本布局，组合输入触发的选区滚动只替换几何读数。
  editor.view.coordsAtPos = () => ({ left: 100, right: 100, top: 100, bottom: 120 });
}
const revealed = () => handle.queryAll('[data-writing-revealed]');
async function hover(element) {
  await act(async () => element.dispatchEvent(new window.MouseEvent('mouseover', { bubbles: true })));
}
async function key(target, key, extra = {}) {
  const event = new window.KeyboardEvent('keydown', { bubbles: true, cancelable: true, ctrlKey: true, shiftKey: true, key, ...extra });
  await act(async () => target.dispatchEvent(event));
  return event;
}
async function advanceIdle(milliseconds) {
  now += milliseconds;
  await act(async () => {
    for (const [id, timer] of idleTimers) {
      if (timer.due > now) continue;
      idleTimers.delete(id);
      timer.callback();
    }
  });
}

test('正文逐块遮挡，悬浮仅揭示当前块；标题与目录同样保护且没有原文悬浮提示', async () => {
  await preferences.updateWritingPrivacyPreferences({ enabled: true });
  await render();
  assert.ok(handle.query('main').className.includes('protected'));
  const paragraphs = body.querySelectorAll('p');
  assert.ok([...paragraphs].every(p => p.hasAttribute('data-writing-private')));
  await hover(paragraphs[0]);
  assert.deepEqual(revealed(), [paragraphs[0]]);
  await hover(paragraphs[1]);
  assert.deepEqual(revealed(), [paragraphs[1]]);
  await hover(body.querySelector('li p'));
  assert.deepEqual(revealed(), [body.querySelector('li p')]);
  await hover(body.querySelector('pre code'));
  assert.deepEqual(revealed(), [body.querySelector('.node-codeBlock')]);
  assert.equal(body.querySelector('pre').hasAttribute('data-writing-private'), false, '代码块仅模糊外层，避免重复叠加');
  const outline = handle.query('[aria-label="文章目录"] button');
  assert.equal(outline.hasAttribute('title'), false);
  await hover(outline);
  assert.deepEqual(revealed(), [outline]);
  await hover(handle.query('[aria-label="标题"]'));
  assert.deepEqual(revealed(), [handle.query('[aria-label="标题"]')]);
  await hover(handle.query('main'));
  assert.equal(revealed().length, 0);
});

test('光标编辑、新段落、滚动与窗口失焦重新计算遮挡，不把标记写入保存结果', async () => {
  await preferences.updateWritingPrivacyPreferences({ enabled: true });
  let saved;
  await render({ onSave: value => { saved = value; } });
  const originalMarkdown = editor.getMarkdown();
  const originalHtml = editor.getHTML();
  await act(async () => {
    body.focus();
    editor.commands.setTextSelection(12);
    editor.commands.insertContent('新字');
  });
  assert.equal(revealed().length, 1);
  assert.equal(revealed()[0].tagName, 'P');
  await act(async () => editor.commands.splitBlock());
  assert.equal(revealed().length, 1);
  assert.ok(body.contains(revealed()[0]));
  assert.ok(revealed()[0].hasAttribute('data-writing-private'));
  await act(async () => handle.query('main > div:last-child').dispatchEvent(new window.Event('scroll')));
  assert.equal(revealed().length, 0);
  assert.equal(idleTimers.size, 0);
  await hover(body.querySelector('p'));
  assert.equal(revealed().length, 1);
  await act(async () => window.dispatchEvent(new window.Event('blur')));
  assert.equal(revealed().length, 0);
  await act(async () => editor.commands.setContent(originalMarkdown, { contentType: 'markdown' }));
  await handle.click('保存');
  assert.equal(saved.body, originalMarkdown.trim());
  assert.equal(saved.html, originalHtml);
  assert.doesNotMatch(saved.html, /data-writing|blur|protected/);
  assert.doesNotMatch(saved.body, /data-writing|blur|protected/);
});

test('默认快捷键在编辑时一键隐藏和恢复，忽略长按、输入法与普通输入并持久化开关', async () => {
  await render();
  assert.equal(handle.query('button[aria-pressed]').getAttribute('aria-pressed'), 'false');
  for (const extra of [{ ctrlKey: false }, { isComposing: true }, { repeat: true }, { altKey: true }]) {
    await key(body, 'h', extra);
    assert.equal(preferences.getWritingPrivacyPreferences().enabled, false);
  }
  assert.equal((await key(body, 'h')).defaultPrevented, true);
  assert.equal(handle.query('button[aria-pressed]').getAttribute('aria-pressed'), 'true');
  assert.equal(revealed().length, 0, '一键隐藏时先隐藏全部块');
  await key(body, 'H', { ctrlKey: false, metaKey: true });
  assert.equal(handle.query('button[aria-pressed]').getAttribute('aria-pressed'), 'false');
  await handle.click('开启防偷窥');
  await handle.unmount(); handle = null;
  await render();
  assert.equal(handle.query('button[aria-pressed]').getAttribute('aria-pressed'), 'true');
  await handle.unmount(); handle = null;
  await render({ privacyAvailable: false });
  assert.equal(handle.query('button[aria-pressed]'), null);
  assert.equal(handle.query('main').className.includes('protected'), false);
  assert.equal((await key(body, 'h')).defaultPrevented, false);
});

test('设置页录入、校验、清除与恢复快捷键，并在编辑页使用新组合', async () => {
  handle = await mount(React.createElement(Provider, null, React.createElement(Settings)));
  const input = handle.query('[aria-label="防偷窥快捷键"]');
  assert.equal(input.value, '⌘/Ctrl + Shift + H');
  await key(input, 's');
  assert.match(handle.query('[role="alert"]').textContent, /保留给常用操作/);
  assert.equal(preferences.getWritingPrivacyPreferences().shortcut, 'h');
  await key(input, 'j');
  assert.equal(input.value, '⌘/Ctrl + Shift + J');
  await handle.unmount(); handle = null;
  await render();
  await key(body, 'h');
  assert.equal(preferences.getWritingPrivacyPreferences().enabled, false);
  await key(body, 'j');
  assert.equal(preferences.getWritingPrivacyPreferences().enabled, true);
  await handle.unmount(); handle = null;
  handle = await mount(React.createElement(Provider, null, React.createElement(Settings)));
  assert.equal(handle.query('[aria-label="防偷窥快捷键"]').value, '⌘/Ctrl + Shift + J');
  await handle.click('清除快捷键');
  assert.equal(handle.query('[aria-label="防偷窥快捷键"]').value, '未设置');
  await handle.unmount(); handle = null;
  await render();
  assert.equal((await key(body, 'j')).defaultPrevented, false);
  assert.equal(preferences.getWritingPrivacyPreferences().enabled, true);
  await handle.unmount(); handle = null;
  handle = await mount(React.createElement(Provider, null, React.createElement(Settings)));
  await handle.click('恢复默认');
  assert.equal(handle.query('[aria-label="防偷窥快捷键"]').value, '⌘/Ctrl + Shift + H');
});

test('持久化失败显示错误并保留原开关，失败后仍可再次保存', async () => {
  await render();
  const prototype = Object.getPrototypeOf(localStorage);
  const original = prototype.setItem;
  prototype.setItem = () => { throw new Error('本机偏好存储不可写'); };
  try {
    await handle.click('开启防偷窥');
    assert.match(handle.query('[role="alert"]').textContent, /本机偏好存储不可写/);
    assert.equal(handle.query('button[aria-pressed]').getAttribute('aria-pressed'), 'false');
    assert.equal(preferences.getWritingPrivacyPreferences().enabled, false);
  } finally { prototype.setItem = original; }
  await handle.click('开启防偷窥');
  assert.equal(handle.query('button[aria-pressed]').getAttribute('aria-pressed'), 'true');
});

test('不回车也会在停止输入 1.5 秒后隐藏，连续输入重新计时，恢复输入再次显示', async () => {
  await preferences.updateWritingPrivacyPreferences({ enabled: true });
  await render();
  await act(async () => { body.focus(); editor.commands.setTextSelection(12); editor.commands.insertContent('继续写'); });
  const paragraph = revealed()[0];
  assert.ok(paragraph);
  const beforeCount = body.querySelectorAll('p').length;
  await advanceIdle(1499);
  assert.deepEqual(revealed(), [paragraph]);
  await act(async () => editor.commands.insertContent('还在写'));
  await advanceIdle(1499);
  assert.deepEqual(revealed(), [paragraph]);
  await advanceIdle(1);
  assert.equal(revealed().length, 0);
  assert.equal(body.querySelectorAll('p').length, beforeCount, '没有通过回车新增段落');
  await act(async () => editor.commands.insertContent('重新输入'));
  assert.deepEqual(revealed(), [paragraph]);
  await hover(body.querySelector('li p'));
  await advanceIdle(1500);
  assert.deepEqual(revealed(), [body.querySelector('li p')], '主动悬浮不会被之前的输入计时遮挡');
});

test('标题输入同样自动隐藏，中文组合期间保持可见、确认后开始计时，关闭模式取消计时', async () => {
  await preferences.updateWritingPrivacyPreferences({ enabled: true });
  await render();
  const title = handle.query('[aria-label="标题"]');
  await act(async () => title.dispatchEvent(new window.InputEvent('input', { bubbles: true, data: '新标题' })));
  assert.deepEqual(revealed(), [title]);
  await advanceIdle(1500);
  assert.equal(revealed().length, 0);
  await act(async () => {
    body.focus();
    body.dispatchEvent(new window.CompositionEvent('compositionstart', { bubbles: true }));
    editor.commands.setTextSelection(12);
    editor.commands.insertContent('拼音输入中');
  });
  assert.equal(revealed().length, 1);
  await advanceIdle(3000);
  assert.equal(revealed().length, 1);
  assert.equal(idleTimers.size, 0);
  await act(async () => body.dispatchEvent(new window.CompositionEvent('compositionend', { bubbles: true, data: '中文' })));
  await advanceIdle(1499);
  assert.equal(revealed().length, 1);
  await advanceIdle(1);
  assert.equal(revealed().length, 0);
  await act(async () => editor.commands.insertContent('完成'));
  assert.equal(idleTimers.size, 1);
  await handle.click('恢复可见');
  assert.equal(idleTimers.size, 0);
  await advanceIdle(1500);
  assert.equal(handle.query('main').className.includes('protected'), false);
});
