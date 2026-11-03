import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, loadModule, mount, resetModules, startHarness, stopHarness } from '../../testing/harness.mjs';

let Page, Provider, preferences, handle, originalImage, originalContext, originalDataUrl, originalCreateUrl, originalRevokeUrl;
const png = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
before(async () => {
  await startHarness({ globals: ['FileReader', 'Image'] });
  originalImage = globalThis.Image;
  originalContext = window.HTMLCanvasElement.prototype.getContext;
  originalDataUrl = window.HTMLCanvasElement.prototype.toDataURL;
  originalCreateUrl = URL.createObjectURL;
  originalRevokeUrl = URL.revokeObjectURL;
});
after(stopHarness);
beforeEach(async () => {
  localStorage.clear(); resetModules();
  ({ FeatureSettingsPage: Page } = await loadModule('/src/components/settings/FeatureSettingsPage.tsx'));
  ({ MessageProvider: Provider } = await loadModule('/src/components/common/Message.tsx'));
  preferences = await loadModule('/src/services/writingEffectPreferences.ts');
  globalThis.Image = class {
    naturalWidth = 200; naturalHeight = 100;
    set src(_) { queueMicrotask(() => this.onload()); }
  };
  window.HTMLCanvasElement.prototype.getContext = () => ({ drawImage() {} });
  window.HTMLCanvasElement.prototype.toDataURL = () => 'data:image/png;base64,iVBORw0KGgo=';
  URL.createObjectURL = () => 'blob:test'; URL.revokeObjectURL = () => {};
  const React = getReact();
  handle = await mount(React.createElement(Provider, null, React.createElement(Page, { tab: 'library' })));
});
afterEach(async () => {
  await handle.unmount();
  globalThis.Image = originalImage;
  window.HTMLCanvasElement.prototype.getContext = originalContext;
  window.HTMLCanvasElement.prototype.toDataURL = originalDataUrl;
  URL.createObjectURL = originalCreateUrl; URL.revokeObjectURL = originalRevokeUrl;
});
async function selectStyle(style) {
  await act(async () => {
    const select = handle.query('#writing-effect-style');
    select.value = style; select.dispatchEvent(new window.Event('change', { bubbles: true }));
  });
}
async function upload(file) {
  const input = handle.query('[aria-label="上传特效图片"]');
  Object.defineProperty(input, 'files', { configurable: true, value: [file] });
  await act(async () => {
    input.dispatchEvent(new window.Event('change', { bubbles: true }));
    await new Promise(resolve => setTimeout(resolve, 20));
  });
}

test('资料设置提供独立配置与同款预览，保存 emoji、风格和开关后重新打开恢复', async () => {
  assert.match(handle.text(), /资料/);
  assert.ok(handle.query('section[aria-label="打字特效"]'));
  await handle.click('🌟');
  assert.equal(handle.queryAll('button[aria-pressed]').find(button => button.textContent === '🌟').getAttribute('aria-pressed'), 'false');
  await selectStyle('emoji');
  await handle.click('预览特效');
  assert.match(handle.text(), /[✨💖🍀🌸🦋🍊🍒🌈🫧🎈💫]/u);
  await act(async () => handle.query('[role="switch"]').click());
  assert.equal(handle.query('[role="switch"]').getAttribute('aria-checked'), 'false');
  await handle.unmount();
  const React = getReact();
  handle = await mount(React.createElement(Provider, null, React.createElement(Page, { tab: 'library' })));
  assert.equal(handle.query('#writing-effect-style').value, 'emoji');
  assert.equal(handle.queryAll('button[aria-pressed]').find(button => button.textContent === '🌟').getAttribute('aria-pressed'), 'false');
  assert.equal(handle.query('[role="switch"]').getAttribute('aria-checked'), 'false');
});

test('上传图片、使用统一缩略图、切换图片模式并删除，配置与界面保持一致', async () => {
  await upload(new File([png], '我的贴纸.png', { type: 'image/png' }));
  assert.ok(handle.query('img[alt="我的贴纸.png"]'));
  assert.equal(preferences.getWritingEffectPreferences().images.length, 1);
  await selectStyle('images');
  await handle.click('预览特效');
  assert.equal(handle.queryAll('img[alt=""]').length, 6);
  await act(async () => handle.query('[aria-label="删除特效图片 我的贴纸.png"]').click());
  assert.equal(handle.query('img[alt="我的贴纸.png"]'), null);
  assert.equal(preferences.getWritingEffectPreferences().images.length, 0);
  assert.equal(handle.query('#writing-effect-style').value, 'mixed');
});

test('不合法图片显示全局错误提示，保留原有配置', async () => {
  await upload(new File(['not-image'], '伪装图片.png', { type: 'image/png' }));
  assert.match(handle.query('[role="alert"]').textContent, /仅支持/);
  assert.equal(preferences.getWritingEffectPreferences().images.length, 0);
});
