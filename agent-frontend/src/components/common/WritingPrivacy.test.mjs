import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, loadModule, mount, resetModules, startHarness, stopHarness } from '../../testing/harness.mjs';

let React, Page, Settings, Provider, preferences, handle, editor, body;
before(async () => { await startHarness(); React = getReact(); window.scrollBy = () => {}; });
beforeEach(async () => {
  localStorage.clear(); resetModules();
  ({ WritingEditorPage: Page } = await loadModule('/src/components/common/WritingEditorPage.tsx'));
  ({ WritingPrivacySettings: Settings } = await loadModule('/src/components/settings/WritingPrivacySettings.tsx'));
  ({ MessageProvider: Provider } = await loadModule('/src/components/common/Message.tsx'));
  preferences = await loadModule('/src/services/writingPrivacyPreferences.ts');
});
afterEach(async () => {
  await handle?.unmount(); handle = null;
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
