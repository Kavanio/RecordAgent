import assert from 'node:assert/strict';
import { test, before, after, beforeEach, afterEach } from 'node:test';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';

let server, dom, React, createRoot, ModelProviderContext, useModel, ModelSettingsPage, ModelInitPage, MessageProvider;
let root, container, model, requests, backend, rejectSave, failLoad;
const originalFetch = globalThis.fetch;
before(async () => {
  dom = new JSDOM('<html><body></body></html>', { url: 'http://localhost/' });
  for (const key of ['window', 'document', 'HTMLElement', 'HTMLInputElement', 'Event', 'MouseEvent', 'localStorage']) globalThis[key] = dom.window[key];
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  React = await import('react');
  ({ createRoot } = await import('react-dom/client'));
  server = await createServer({ server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  ({ ModelProviderContext, useModel } = await server.ssrLoadModule('/src/context/ModelContext.tsx'));
  ({ ModelSettingsPage } = await server.ssrLoadModule('/src/components/model/ModelSettingsPage.tsx'));
  ({ ModelInitPage } = await server.ssrLoadModule('/src/components/model/ModelInitPage.tsx'));
  ({ MessageProvider } = await server.ssrLoadModule('/src/components/common/Message.tsx'));
});
after(async () => { await server?.close(); dom?.window.close(); globalThis.fetch = originalFetch; });
beforeEach(async () => {
  localStorage.clear(); requests = []; rejectSave = false; failLoad = false; model = undefined;
  backend = { activeVendor: '', activeModel: '', vendor: '', name: '', apiKey: '', providers: [], temperature: 0.7, stream: true };
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), ...options });
    let data = {}, code = 200;
    if (String(url).endsWith('/full-config')) {
      if (options.method === 'POST') {
        if (rejectSave) code = 400;
        else backend = JSON.parse(options.body);
        data = '已保存';
      } else if (failLoad) return { ok: false };
      else data = backend;
    } else if (String(url).endsWith('/vendors')) data = ['openai', 'anthropic', 'custom'];
    else if (String(url).endsWith('/test-connection')) data = { success: false, message: '模型请求失败' };
    return { ok: true, json: async () => ({ code, message: code === 400 ? '配置无效' : '', data }) };
  };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
});
afterEach(async () => { await React.act(async () => root?.unmount()); container?.remove(); });
function Probe() { model = useModel(); return null; }
async function mount(settings = false) {
  await React.act(async () => root.render(React.createElement(MessageProvider, null,
    React.createElement(ModelProviderContext, null, React.createElement(Probe),
      settings ? React.createElement(ModelSettingsPage, { onBack() {} }) : null))));
}
const custom = (protocol = 'openai', baseUrl = 'https://gateway.example/v1') => ({ vendor: 'custom', baseUrl, protocol,
  apiKey: 'test-key', modelId: 'same-model', name: '自定义模型', makeDefault: true });
async function change(id, value, select = false) {
  const element = document.getElementById(id); assert.ok(element, id);
  await React.act(async () => {
    if (select) element.value = value;
    else Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(element, value);
    element.dispatchEvent(new dom.window.Event(select ? 'change' : 'input', { bubbles: true }));
  });
}
async function click(text) {
  const element = [...container.querySelectorAll('button')].find(b => b.textContent === text);
  assert.ok(element, text); await React.act(async () => element.click());
}

test('挂载只读取后端，不用本地缓存覆盖权威配置', async () => {
  localStorage.setItem('butvan_agent_providers_v2', '[{"id":"stale"}]');
  await mount();
  assert.deepEqual(model.providers, []);
  assert.equal(requests.filter(r => r.method === 'POST').length, 0);
});

test('自定义连接独立保存并原子选择，传递协议、地址与密钥', async () => {
  await mount(); let first, second;
  await React.act(async () => { first = await model.addModelItem(custom()); });
  await React.act(async () => { second = await model.addModelItem(custom('anthropic', 'https://other.example/v1')); });
  assert.notEqual(first, second);
  assert.equal(model.providers.length, 2);
  assert.equal(backend.activeVendor, second);
  assert.equal(backend.providers[0].protocol, 'openai');
  assert.equal(backend.providers[1].protocol, 'anthropic');
  assert.equal(backend.providers[1].baseUrl, 'https://other.example/v1');
  await React.act(async () => { await model.selectActiveModel(first, 'same-model'); });
  assert.equal(backend.activeVendor, first);
  assert.equal(backend.apiKey, 'test-key');
  assert.equal(backend.protocol, 'openai');
});

test('新建同时设为默认不会请求旧的 Provider，保存失败保留原状态', async () => {
  await mount(); rejectSave = true;
  await React.act(async () => { await assert.rejects(model.addModelItem(custom()), /配置无效/); });
  assert.equal(model.providers.length, 0);
  assert.equal(model.activeProviderId, '');
  assert.equal(requests.filter(r => r.url.endsWith('/agent/model')).length, 0);
});

test('编辑后持久化地址与协议，删除最后一个模型明确清空配置', async () => {
  await mount(); let id;
  await React.act(async () => { id = await model.addModelItem(custom()); });
  await React.act(async () => { await model.updateModelItem({ providerId: id, modelId: 'same-model' }, custom('anthropic', 'https://new.example')); });
  assert.equal(model.activeProviderId, id);
  assert.equal(backend.providers[0].baseUrl, 'https://new.example');
  assert.equal(backend.protocol, 'anthropic');
  await React.act(async () => { await model.deleteModelItem(id, 'same-model'); });
  assert.deepEqual(backend.providers, []);
  assert.equal(backend.vendor, '');
});

test('持久化不携带账户渠道快照，并发操作按顺序合并最新模型状态', async () => {
  backend.account = { revision: 1 }; backend.feishu = { enabled: false };
  await mount();
  await React.act(async () => {
    await Promise.all([model.addModelItem(custom()), model.addModelItem(custom('anthropic'))]);
  });
  assert.equal(backend.providers.length, 2);
  const payload = JSON.parse(requests.find(r => r.method === 'POST').body);
  assert.equal('account' in payload, false);
  assert.equal('feishu' in payload, false);
});

test('读取失败禁止写回；不会清空后端模型', async () => {
  failLoad = true; await mount();
  await assert.rejects(model.addModelItem(custom()), /尚未读取/);
  assert.equal(requests.filter(r => r.method === 'POST').length, 0);
  failLoad = false;
  await React.act(async () => { await model.addModelItem(custom()); });
  assert.equal(backend.providers.length, 1);
});

test('连接测试只调用后端，传递真实模型 ID，不把失败当成功', async () => {
  await mount();
  const result = await model.testConnectionByUrl('https://gateway.example/v1', 'test-key', 'custom', 'same-model', 'anthropic');
  assert.equal(result.success, false);
  const request = requests.find(r => r.url.endsWith('/test-connection'));
  assert.equal(JSON.parse(request.body).protocol, 'anthropic');
  assert.equal(JSON.parse(request.body).modelName, 'same-model');
  assert.equal(requests.some(r => r.url.startsWith('https://gateway.example')), false);
});

test('设置页新增自定义模型展示并保存请求格式和 API 地址', async () => {
  await mount(true); await click('添加模型');
  await change('create-model-vendor', 'custom', true);
  assert.equal(document.getElementById('create-model-base-url').value, '');
  await change('create-model-name', '我的模型');
  await change('create-model-id', 'my-model');
  await change('create-model-protocol', 'anthropic', true);
  await change('create-model-base-url', 'https://custom.example/v1');
  await change('create-model-api-key', 'test-key');
  await click('保存并创建');
  assert.equal(backend.providers[0].protocol, 'anthropic');
  assert.equal(backend.providers[0].baseUrl, 'https://custom.example/v1');
  assert.equal(document.getElementById('detail-model-base-url').value, 'https://custom.example/v1');
});

test('首次引导支持自定义 API，提交两种格式所需字段', async () => {
  await React.act(async () => root.render(React.createElement(ModelInitPage, { vendors: ['custom'], onSuccess() {} })));
  await change('init-model-protocol', 'anthropic', true);
  await change('init-model-base-url', 'https://init.example/v1');
  const inputs = [...container.querySelectorAll('input')];
  await React.act(async () => {
    for (const input of inputs.filter(i => !i.id)) {
      Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, input.type === 'password' ? 'test-key' : 'my-model');
      input.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
    }
  });
  await React.act(async () => container.querySelector('form').dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  const body = JSON.parse(requests.find(r => r.url.endsWith('/agent/model')).body);
  assert.equal(body.vendor, 'custom'); assert.equal(body.protocol, 'anthropic');
  assert.equal(body.baseUrl, 'https://init.example/v1');
});

test('图片识别标注随模型保存并在编辑后保留', async () => {
  await mount(); let id;
  await React.act(async () => { id = await model.addModelItem({ ...custom(), supportsVision: true }); });
  assert.equal(backend.providers[0].models[0].supportsVision, true);
  await React.act(async () => {
    await model.updateModelItem({ providerId: id, modelId: 'same-model' },
      { ...custom('anthropic', 'https://new.example/v1'), supportsVision: true });
  });
  assert.equal(backend.providers[0].models[0].supportsVision, true);
  assert.equal(model.getAllModels()[0].supportsVision, true);
  await React.act(async () => {
    await model.updateModelItem({ providerId: id, modelId: 'same-model' }, { ...custom(), supportsVision: false });
  });
  assert.equal(backend.providers[0].models[0].supportsVision, false);
});

test('设置页可标注图片识别能力并提交到后端', async () => {
  await mount(true); await click('添加模型');
  await change('create-model-vendor', 'custom', true);
  await change('create-model-name', '视觉模型');
  await change('create-model-id', 'vision-model');
  await change('create-model-base-url', 'https://custom.example/v1');
  await change('create-model-api-key', 'test-key');
  const toggle = document.querySelector('[aria-label="支持图片识别"]');
  assert.ok(toggle);
  await React.act(async () => toggle.click());
  await click('保存并创建');
  assert.equal(backend.providers[0].models[0].supportsVision, true);
  assert.equal(document.querySelector('[aria-label="支持图片识别"]').getAttribute('aria-checked'), 'true');
});
