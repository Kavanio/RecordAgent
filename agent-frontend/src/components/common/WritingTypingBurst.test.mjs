import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, loadModule, mount, startHarness, stopHarness } from '../../testing/harness.mjs';

let React, WritingEditorPage, MessageProvider, handle, body, editor, frames, timers, media, listeners, preferences;
let originalRaf, originalCancelRaf, originalTimeout, originalClearTimeout;

before(async () => {
  await startHarness();
  React = getReact();
  ({ WritingEditorPage } = await loadModule('/src/components/common/WritingEditorPage.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
  preferences = await loadModule('/src/services/writingEffectPreferences.ts');
  originalRaf = window.requestAnimationFrame;
  originalCancelRaf = window.cancelAnimationFrame;
  originalTimeout = window.setTimeout;
  originalClearTimeout = window.clearTimeout;
  window.scrollBy = () => {};
});
after(async () => { await stopHarness(); });
beforeEach(async () => {
  await preferences.updateWritingEffectPreferences({ enabled: true, style: 'mixed', emojis: [...preferences.WRITING_EFFECT_EMOJIS], images: [] });
  frames = new Map(); timers = new Map(); listeners = new Set(); let id = 0;
  window.requestAnimationFrame = callback => { const key = ++id; frames.set(key, callback); return key; };
  window.cancelAnimationFrame = key => frames.delete(key);
  window.setTimeout = (callback, duration, ...args) => {
    if (duration !== 560) return originalTimeout(callback, duration, ...args);
    const key = ++id; timers.set(key, callback); return key;
  };
  window.clearTimeout = key => { if (!timers.delete(key)) originalClearTimeout(key); };
  media = { matches: false, addEventListener: (_, fn) => listeners.add(fn), removeEventListener: (_, fn) => listeners.delete(fn) };
  window.matchMedia = () => media;
});
afterEach(async () => {
  await handle?.unmount();
  handle = null;
  assert.equal(frames.size, 0);
  assert.equal(timers.size, 0);
  assert.equal(listeners.size, 0);
  window.requestAnimationFrame = originalRaf;
  window.cancelAnimationFrame = originalCancelRaf;
  window.setTimeout = originalTimeout;
  window.clearTimeout = originalClearTimeout;
  // Tiptap 焦点命令使用全局动画帧；在销毁测试 window 前排空它们。
  await act(async () => { await new Promise(resolve => originalRaf.call(window, resolve)); });
});

async function render(props = {}) {
  handle = await mount(React.createElement(MessageProvider, null, React.createElement(WritingEditorPage, {
    backLabel: '资料', identity: '新增资料', detail: '2026-11-02', onBack() {}, onSave() {}, ...props,
  })));
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); });
  body = handle.query('[role="textbox"][aria-label="正文"]');
  editor = body.editor;
  // jsdom 没有文本布局，只有几何读数替身；正文、输入监听与动画层均挂载真实实现。
  editor.view.coordsAtPos = () => ({ left: 120, right: 120, top: 100, bottom: 120 });
  await act(async () => body.focus());
}
async function input(data, inputType = 'insertText', isComposing = false, target = body) {
  await act(async () => target.dispatchEvent(new window.InputEvent('input', { bubbles: true, data, inputType, isComposing })));
}
async function advance() {
  const callbacks = [...frames.values()]; frames.clear();
  await act(async () => callbacks.forEach(callback => callback(0)));
}
const bursts = () => [...document.querySelectorAll('[data-typing-burst]')];

test('字母和数字在当前光标处炸开微型图标，动画不进入正文或保存结果', async () => {
  let saved;
  await render({ onSave: value => { saved = value; } });
  await act(async () => editor.commands.insertContent('a1'));
  await input('a'); await advance();
  assert.equal(bursts().length, 1);
  assert.equal(bursts()[0].style.left, '120px');
  assert.equal(bursts()[0].style.top, '110px');
  assert.equal(bursts()[0].firstElementChild.style.top, '-22px', '粒子在字形顶部之外保留空隙');
  assert.equal(bursts()[0].querySelectorAll('svg').length, 4);
  assert.equal(bursts()[0].parentElement.getAttribute('aria-hidden'), 'true');
  editor.view.coordsAtPos = () => ({ left: 150, right: 150, top: 130, bottom: 150 });
  await input('1'); await advance();
  assert.equal(bursts().length, 2);
  assert.equal(bursts()[1].style.left, '150px');
  await handle.click('保存');
  assert.equal(saved.body, 'a1');
  assert.equal(saved.html, '<p>a1</p>');
  const callbacks = [...timers.values()]; timers.clear();
  await act(async () => callbacks.forEach(callback => callback()));
  assert.equal(bursts().length, 0);
});

test('中文拼音组合期间不触发，确认上屏与尾随 input 合并为一次', async () => {
  await render();
  await act(async () => body.dispatchEvent(new window.CompositionEvent('compositionstart', { bubbles: true })));
  await input('n', 'insertCompositionText', true);
  await input('ni', 'insertCompositionText', true);
  await advance();
  assert.equal(bursts().length, 0);
  await act(async () => body.dispatchEvent(new window.CompositionEvent('compositionend', { bubbles: true, data: '你' })));
  await input('你', 'insertText');
  assert.equal(frames.size, 1);
  await advance();
  assert.equal(bursts().length, 1);
});

test('特效锚定刚输入文字的末尾，不跟随下一帧前发生的光标移动', async () => {
  await render();
  await act(async () => editor.commands.insertContent('ab'));
  editor.view.coordsAtPos = position => ({ left: position * 10, right: position * 10, top: 100, bottom: 120 });
  await input('b');
  await act(async () => editor.commands.setTextSelection(1));
  await advance();
  assert.equal(bursts()[0].style.left, '30px');
});

test('软换行取最后输入字形的右边缘，同帧连续输入采用最后一个字的位置', async () => {
  await render();
  await act(async () => editor.commands.insertContent('ab'));
  const original = window.Range.prototype.getClientRects;
  let lastRight = 75;
  window.Range.prototype.getClientRects = () => [{ left: 60, right: lastRight, top: 90, bottom: 110, height: 20 }];
  try {
    const text = body.querySelector('p').firstChild;
    window.getSelection().collapse(text, 2);
    await input('b');
    lastRight = 90;
    await input('c');
    await advance();
    assert.equal(bursts().length, 1);
    assert.equal(bursts()[0].style.left, '90px');
    assert.equal(bursts()[0].style.top, '100px');
    assert.equal(bursts()[0].firstElementChild.style.top, '-22px');
    window.Range.prototype.getClientRects = () => [{ left: 60, right: 90, top: 80, bottom: 120, height: 40 }];
    await input('大'); await advance();
    assert.equal(bursts()[1].firstElementChild.style.top, '-32px', '大字号按字高增加避让空间');
  } finally {
    if (original) window.Range.prototype.getClientRects = original;
    else delete window.Range.prototype.getClientRects;
  }
});

test('删除、粘贴、撤销、空格及光标移动均不产生特效', async () => {
  await render();
  for (const inputType of ['deleteContentBackward', 'insertFromPaste', 'historyUndo', 'insertParagraph']) {
    await input('文本', inputType);
  }
  await input(' ');
  await act(async () => body.dispatchEvent(new window.KeyboardEvent('keydown', { bubbles: true, key: 'ArrowLeft' })));
  assert.equal(frames.size, 0);
  assert.equal(bursts().length, 0);
});

test('高速输入最多保留三组避免密集遮字，滚动清除旧位置特效且卸载取消待执行帧', async () => {
  await render();
  for (let i = 0; i < 12; i++) { await input(String(i)); await advance(); }
  assert.equal(bursts().length, 3);
  assert.equal(timers.size, 3);
  await act(async () => window.dispatchEvent(new window.Event('scroll')));
  assert.equal(bursts().length, 0);
  assert.equal(timers.size, 0);
  await input('下一字');
  assert.equal(frames.size, 1);
  await handle.unmount(); handle = null;
  assert.equal(frames.size, 0);
});

test('尊重减少动态效果设置，切换时停止在播动画；坐标不可用不影响编辑', async () => {
  media.matches = true;
  await render();
  await input('1');
  assert.equal(frames.size, 0);
  media.matches = false;
  await input('2'); await advance();
  assert.equal(bursts().length, 1);
  media.matches = true;
  await act(async () => listeners.forEach(fn => fn()));
  assert.equal(bursts().length, 0);
  media.matches = false;
  editor.view.coordsAtPos = () => { throw new Error('文本布局暂不可用'); };
  await input('3'); await advance();
  assert.equal(bursts().length, 0);
  await act(async () => editor.commands.insertContent('仍然可编辑'));
  assert.equal(body.textContent, '仍然可编辑');
});

test('编辑器接收设置变化，关闭即清理特效，emoji 与自定义图片使用同一爆点', async () => {
  await render();
  await act(async () => preferences.updateWritingEffectPreferences({ style: 'emoji', emojis: ['🌸'] }));
  await input('花'); await advance();
  assert.equal(bursts()[0].textContent, '🌸'.repeat(8));
  await act(async () => preferences.updateWritingEffectPreferences({ enabled: false }));
  assert.equal(bursts().length, 0);
  assert.equal(timers.size, 0);
  await input('关'); assert.equal(frames.size, 0);
  await act(async () => preferences.updateWritingEffectPreferences({ enabled: true, style: 'images', images: [
    { id: 'sticker', name: '贴纸.png', dataUrl: 'data:image/png;base64,iVBORw0KGgo=' },
  ] }));
  await input('图'); await advance();
  assert.equal(bursts()[0].querySelectorAll('img').length, 8);
  assert.equal(bursts()[0].style.left, '120px');
});
