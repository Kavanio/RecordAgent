import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount, getReact } from '../../testing/harness.mjs';
let Content;
before(async () => {
  await startHarness();
  ({ UserMessageContent: Content } = await loadModule('/src/components/chat/UserMessageContent.tsx'));
});
after(stopHarness);

test('资料引用消息同样展示图片，不因特殊分支丢失附件', async () => {
  const host = await mount(getReact().createElement(Content, {
    content: '[资料：方案] 帮我比较', images: [{ name: 'pic.png', dataUrl: 'data:image/png;base64,AA==', mediaType: 'image/png' }],
  }));
  try {
    assert.equal(host.query('img').alt, 'pic.png');
    assert.match(host.text(), /方案/); assert.match(host.text(), /帮我比较/);
  } finally { await host.unmount(); }
});

test('没有附件时保留普通文本', async () => {
  const host = await mount(getReact().createElement(Content, { content: 'hello\nworld' }));
  try { assert.equal(host.text(), 'hello\nworld'); assert.equal(host.query('img'), null); }
  finally { await host.unmount(); }
});
