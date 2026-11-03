import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { loadModule, startHarness, stopHarness } from '../testing/harness.mjs';

let importImage, originalImage, originalCreateUrl, originalRevokeUrl, originalContext, originalDataUrl;
let draw, decoded, revoked, dimensions, decodeError;
const png = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
before(async () => {
  await startHarness({ globals: ['FileReader', 'Image'] });
  ({ importWritingEffectImage: importImage } = await loadModule('/src/services/writingEffectImages.ts'));
  originalImage = globalThis.Image;
  originalCreateUrl = URL.createObjectURL;
  originalRevokeUrl = URL.revokeObjectURL;
  originalContext = window.HTMLCanvasElement.prototype.getContext;
  originalDataUrl = window.HTMLCanvasElement.prototype.toDataURL;
});
after(stopHarness);
beforeEach(() => {
  draw = []; decoded = []; revoked = []; dimensions = [200, 100]; decodeError = false;
  URL.createObjectURL = () => 'blob:local-test';
  URL.revokeObjectURL = url => revoked.push(url);
  globalThis.Image = class {
    naturalWidth = dimensions[0]; naturalHeight = dimensions[1];
    set src(value) { decoded.push(value); queueMicrotask(() => decodeError ? this.onerror() : this.onload()); }
  };
  window.HTMLCanvasElement.prototype.getContext = () => ({ drawImage: (...args) => draw.push(args) });
  window.HTMLCanvasElement.prototype.toDataURL = function (type) {
    assert.equal(this.width, 48); assert.equal(this.height, 48); assert.equal(type, 'image/png');
    return 'data:image/png;base64,iVBORw0KGgo=';
  };
});
afterEach(() => {
  globalThis.Image = originalImage;
  URL.createObjectURL = originalCreateUrl;
  URL.revokeObjectURL = originalRevokeUrl;
  window.HTMLCanvasElement.prototype.getContext = originalContext;
  window.HTMLCanvasElement.prototype.toDataURL = originalDataUrl;
});

test('按实际文件头识别，等比缩小居中到统一透明画布并释放临时资源', async () => {
  const image = await importImage(new File([png], '贴纸.bin', { type: 'application/octet-stream' }));
  assert.equal(image.name, '贴纸.bin');
  assert.equal(image.dataUrl, 'data:image/png;base64,iVBORw0KGgo=');
  assert.deepEqual(draw[0].slice(1), [0, 12, 48, 24]);
  assert.deepEqual(revoked, ['blob:local-test']);
});

test('拒绝超限文件与伪装格式，不进入图片解码', async () => {
  await assert.rejects(importImage(new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'big.png')), /2 MB/);
  await assert.rejects(importImage(new File(['<svg/>'], 'fake.png', { type: 'image/png' })), /仅支持/);
  assert.equal(decoded.length, 0);
});

test('解码失败或像素超限时返回中文错误并释放临时 URL', async () => {
  decodeError = true;
  await assert.rejects(importImage(new File([png], 'broken.png')), /无法解码/);
  assert.deepEqual(revoked, ['blob:local-test']);
  decodeError = false; dimensions = [9000, 9000];
  await assert.rejects(importImage(new File([png], 'huge.png')), /分辨率过大/);
  assert.equal(revoked.length, 2);
});
