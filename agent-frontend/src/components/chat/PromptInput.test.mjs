import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, loadModule, mount, startHarness, stopHarness, useFetchStub, jsonResponse } from '../../testing/harness.mjs';

let React, PromptInput, useChatImages, ModelProviderContext, MessageProvider;
let host, draft, reads, stub, originalReader, sends;
before(async () => {
  await startHarness();
  React = getReact();
  ({ PromptInput } = await loadModule('/src/components/chat/PromptInput.tsx'));
  ({ useChatImages } = await loadModule('/src/components/chat/useChatImages.ts'));
  ({ ModelProviderContext } = await loadModule('/src/context/ModelContext.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
  originalReader = globalThis.FileReader;
});
after(async () => { globalThis.FileReader = originalReader; await stopHarness(); });
beforeEach(() => {
  reads = []; sends = 0;
  stub = useFetchStub(() => jsonResponse({ providers: [], activeVendor: '', activeModel: '' }));
  globalThis.FileReader = class {
    readAsDataURL(file) { reads.push({ reader: this, file }); }
  };
});
afterEach(async () => { await host?.unmount(); stub.restore(); });

function Composer({ session }) {
  draft = useChatImages(session);
  return React.createElement(PromptInput, {
    value: '', onValueChange() {}, onSend() { sends++; draft.clear(); },
    onOpenSettings() {}, permissionMode: 'ASK', onPermissionModeChange() {},
    images: draft.images, imagesLoading: draft.reading, onAddImages: draft.addFiles, onRemoveImage: draft.remove,
  });
}
function tree(session) {
  return React.createElement(MessageProvider, null,
    React.createElement(ModelProviderContext, null, React.createElement(Composer, { session })));
}
async function open() { host = await mount(tree('one')); }
async function choose(files) {
  const input = host.query('input[type="file"]');
  await act(async () => {
    Object.defineProperty(input, 'files', { value: files, configurable: true });
    input.dispatchEvent(new Event('change', { bubbles: true }));
    await Promise.resolve();
  });
}
async function finish(index, data = 'AA==') {
  await act(async () => {
    const reader = reads[index].reader;
    reader.result = `data:application/octet-stream;base64,${data}`;
    reader.onload();
    await Promise.resolve();
  });
}
const image = (name = 'one.png', type = 'image/png') => new File(['abcd'], name, { type });

test('选择图片后只发图片也能发送，发送前后可预览和移除', async () => {
  await open(); await choose([image()]);
  assert.equal(host.query('[aria-label="发送消息"]').disabled, true);
  await finish(0);
  assert.equal(host.query('img').alt, 'one.png');
  assert.equal(host.query('[aria-label="发送消息"]').disabled, false);
  await act(async () => host.query('[aria-label="移除第 1 张图片"]').click());
  assert.equal(host.query('img'), null);
  assert.equal(host.query('[aria-label="发送消息"]').disabled, true);
  await choose([image()]); await finish(1);
  await act(async () => host.query('[aria-label="发送消息"]').click());
  assert.equal(sends, 1); assert.equal(host.query('img'), null);
});

test('连续选择串行追加，不会被旧闭包覆盖；读取期间 Enter 不能发送', async () => {
  await open(); await choose([image('a.png')]); await choose([image('b.png')]);
  assert.equal(reads.length, 1);
  await act(async () => host.query('textarea').dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true })));
  assert.equal(sends, 0);
  await finish(0); assert.equal(reads.length, 2);
  await finish(1);
  assert.deepEqual(host.queryAll('img').map(img => img.alt), ['a.png', 'b.png']);
});

test('切会话时清草稿，旧会话读图迟到也不能进入新会话', async () => {
  await open(); await choose([image('old.png')]);
  await host.render(tree('two'));
  assert.equal(host.query('img'), null);
  await finish(0);
  assert.equal(host.query('img'), null); assert.equal(draft.images.length, 0);
  await choose([image('new.png')]); await finish(1);
  assert.equal(host.query('img').alt, 'new.png');
  await host.render(tree('one'));
  assert.equal(host.query('img'), null);
});

test('粘贴截图支持空 MIME 扩展名兜底，超过数量和大小会显示中文提示', async () => {
  await open();
  const file = image('capture.PNG', '');
  await act(async () => {
    const event = new Event('paste', { bubbles: true, cancelable: true });
    Object.defineProperty(event, 'clipboardData', { value: { files: [file] } });
    host.query('textarea').dispatchEvent(event);
    assert.equal(event.defaultPrevented, true);
  });
  await finish(0);
  assert.equal(draft.images[0].mediaType, 'image/png');
  assert.match(host.query('img').src, /^data:image\/png;base64,/);
  await choose([new File(['bad'], 'bad.pdf', { type: 'application/pdf' })]);
  assert.match(document.body.textContent, /仅支持 PNG/);
  const large = image('large.png'); Object.defineProperty(large, 'size', { value: 10 * 1024 * 1024 + 1 });
  await choose([large]); assert.match(document.body.textContent, /10 MiB/);
  for (const name of ['b.png', 'c.png', 'd.png']) {
    await choose([image(name)]); await finish(reads.length - 1);
  }
  assert.equal(host.query('[aria-label="添加图片"]').disabled, true);
  await choose([image('e.png')]); assert.match(document.body.textContent, /最多发送 4 张/);
});

test('原始字节总量超过 12 MiB 时不再读取下一张', async () => {
  await open();
  const big = image('big.png'); Object.defineProperty(big, 'size', { value: 8 * 1024 * 1024 });
  await choose([big]); await finish(0, 'A'.repeat(11184811) + '=');
  const extra = image('extra.png'); Object.defineProperty(extra, 'size', { value: 5 * 1024 * 1024 });
  await choose([extra]);
  assert.equal(reads.length, 1);
  assert.match(document.body.textContent, /总大小不能超过 12 MiB/);
});
