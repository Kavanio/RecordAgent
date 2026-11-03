import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { act, getReact, loadModule, mount, startHarness, stopHarness, useFetchStub } from '../../testing/harness.mjs';

let RecordEditor, MessageProvider, handle, fetchStub;
const tabs = [
  { id: 'quick', name: '随记', systemKey: 'quick', sortOrder: 0 },
  { id: 'reading', name: '读书心得', systemKey: 'reading', sortOrder: 1 },
];

before(async () => {
  await startHarness();
  ({ RecordEditor } = await loadModule('/src/components/record/RecordEditor.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
  fetchStub = useFetchStub();
});
afterEach(async () => {
  await handle?.unmount();
  handle = null;
  // 清空 Tiptap focus 命令排队的动画帧，再允许公共引导销毁 window。
  await act(async () => { await new Promise(resolve => window.requestAnimationFrame(resolve)); });
});
after(async () => { fetchStub?.restore(); await stopHarness(); });

async function render(props = {}) {
  const React = getReact();
  handle = await mount(React.createElement(MessageProvider, null, React.createElement(RecordEditor, {
    date: '2026-11-02', tabs, initialTabId: 'quick', saving: false, onSave: async () => {}, onBack() {}, ...props,
  })));
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); });
  return handle.query('[role="textbox"][aria-label="正文"]');
}

// 走真实编辑视图的输入处理链，与浏览器键入一样逐字符触发 Markdown 输入规则。
async function type(body, text) {
  const view = body.editor.view;
  await act(async () => {
    for (const char of text) {
      const { from, to } = view.state.selection;
      const handled = view.someProp('handleTextInput', handler => handler(view, from, to, char,
        () => view.state.tr.insertText(char, from, to)));
      if (!handled) view.dispatch(view.state.tr.insertText(char, from, to));
    }
  });
}

async function paste(body, text, extra = {}) {
  const event = new window.Event('paste', { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'clipboardData', { value: { getData: type => extra[type] ?? (type === 'text/plain' ? text : '') } });
  await act(async () => body.dispatchEvent(event));
  return event;
}

test('输入 Markdown 标题与加粗后直接显示排版，并同步目录和保存正文', async () => {
  let saved;
  const body = await render({ onSave: async value => { saved = value; } });
  await type(body, '## 实时标题');
  assert.equal(body.querySelector('h2')?.textContent, '实时标题');
  assert.match(handle.query('[aria-label="文章目录"]').textContent, /实时标题/);
  await act(async () => body.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true })));
  await type(body, '**加粗内容**');
  assert.equal(body.querySelector('strong')?.textContent, '加粗内容');
  await handle.click('保存');
  assert.match(saved.contentText, /## 实时标题/);
  assert.match(saved.contentText, /\*\*加粗内容\*\*/);
  assert.match(saved.contentHtml, /<h2>实时标题<\/h2>/);
});

test('粘贴 Markdown 在正文内即时渲染标题、列表和待办，保存后重新打开保留格式', async () => {
  let saved;
  const body = await render({ onSave: async value => { saved = value; } });
  await paste(body, '## 粘贴标题\n\n- 列表内容\n\n- [ ] 待办内容\n\n> 引用内容');
  assert.equal(body.querySelector('h2')?.textContent, '粘贴标题');
  assert.match(body.querySelector('ul')?.textContent, /列表内容/);
  assert.equal(body.querySelector('input[type="checkbox"]')?.checked, false);
  assert.match(body.querySelector('blockquote')?.textContent, /引用内容/);
  await handle.click('保存');
  await handle.unmount();
  handle = null;
  fetchStub.respond(async () => ({ ok: true, json: async () => ({ code: 200, data: [] }) }));
  const reopened = await render({ entry: { ...saved, id: 'saved', tags: [] } });
  assert.equal(reopened.querySelector('h2')?.textContent, '粘贴标题');
  assert.ok(reopened.querySelector('input[type="checkbox"]'));
});

test('代码块内粘贴 Markdown 保持代码原文，富文本粘贴保留格式', async () => {
  let body = await render();
  await paste(body, '```text\n原始代码\n```');
  assert.ok(body.querySelector('pre code'));
  await paste(body, '\n## 仍是代码');
  assert.match(body.querySelector('pre code').textContent, /## 仍是代码/);
  assert.equal(body.querySelector('h2'), null);
  await handle.unmount();
  handle = null;
  body = await render();
  await paste(body, '**纯文本版本**', { 'text/html': '<p><em>富文本内容</em></p>' });
  assert.equal(body.querySelector('em')?.textContent, '富文本内容');
});

test('分类与日期只在正文之后的资料属性内出现，修改后随资料保存', async () => {
  let saved;
  const body = await render({ onSave: async value => { saved = value; } });
  const properties = handle.query('[role="group"][aria-label="资料属性"]');
  assert.ok(body.compareDocumentPosition(properties) & window.Node.DOCUMENT_POSITION_FOLLOWING);
  assert.equal(handle.queryAll('select[aria-label="资料分类"]').length, 1);
  assert.equal(handle.queryAll('input[aria-label="资料日期"]').length, 1);
  assert.ok(properties.querySelector('select[aria-label="资料分类"]'));
  assert.ok(properties.querySelector('input[aria-label="资料日期"]'));
  await act(async () => {
    const category = properties.querySelector('select');
    category.value = 'reading';
    category.dispatchEvent(new window.Event('change', { bubbles: true }));
    const date = properties.querySelector('input[type="date"]');
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(date, '2026-11-01');
    date.dispatchEvent(new window.Event('input', { bubbles: true }));
  });
  await type(body, '新的资料正文');
  await handle.click('保存');
  assert.equal(saved.tabId, 'reading');
  assert.equal(saved.type, 'reading');
  assert.equal(saved.recordDate, '2026-11-01');
});
