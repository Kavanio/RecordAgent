import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act, resetModules } from '../../testing/harness.mjs';
let view;
before(() => startHarness());
after(() => stopHarness());
afterEach(async () => {
  await view?.unmount(); view = null;
  delete window.__TAURI_INTERNALS__;
});
test('重置屏障卸载旧会话，完成后用新代次挂载，旧组件不能恢复正文', async () => {
  resetModules();
  let state = { resetId: null, generation: 0, status: 'IDLE', message: '', completedSteps: [], retryable: false, remoteStatus: 'NOT_INTEGRATED' };
  useFetchStub(() => jsonResponse(state));
  const React = getReact();
  const { AgentResetBoundary } = await loadModule('/src/components/reset/AgentResetBoundary.tsx');
  const { fetchAgentReset } = await loadModule('/src/services/agentResetService.ts');
  let mounts = 0, oldUpdate;
  function Child() {
    const [body, update] = React.useState('新会话');
    React.useEffect(() => { mounts++; if (mounts === 1) oldUpdate = update; }, []);
    return React.createElement('p', null, body);
  }
  view = await mount(React.createElement(AgentResetBoundary, null, React.createElement(Child)));
  assert.equal(mounts, 1);
  await act(async () => { oldUpdate('旧正文'); });
  state = { ...state, generation: 1, status: 'FAILED', resetId: 'reset-1', retryable: true, message: '未完成，屏障有效' };
  await act(async () => { await fetchAgentReset(); });
  assert.doesNotMatch(view.text(), /旧正文/);
  assert.match(view.text(), /未完成，屏障有效/);
  state = { ...state, status: 'LOCAL_COMPLETE', retryable: false };
  await act(async () => { await fetchAgentReset(); });
  assert.match(view.text(), /本地重置完成/);
  await view.click('开始新对话');
  assert.equal(mounts, 2);
  await act(async () => { oldUpdate('迟到旧正文'); });
  assert.equal(view.text(), '新会话');
});

function nativeConnection() {
  const calls = [];
  window.__TAURI_INTERNALS__ = { invoke: async (command, args) => {
    calls.push({ command, args });
    if (command === 'get_backend_connection') return { mode: 'remote', remoteUrl: 'https://old.example.com',
      savedRemoteUrl: 'https://old.example.com', hasApiToken: true, restartRequired: false };
    if (command === 'save_remote_backend_url') return true;
    if (command === 'restart_application') return;
    throw new Error(`未设置原生命令响应：${command}`);
  } };
  return calls;
}
async function clickDialog(label) {
  const button = [...document.querySelector('[role="dialog"]').querySelectorAll('button')]
    .find(item => item.textContent === label);
  assert.ok(button, `找不到弹窗按钮：${label}`);
  await act(async () => button.click());
}
async function inputValue(id, value) {
  const input = document.querySelector(id);
  assert.ok(input, `找不到字段：${id}`);
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(input, value);
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
  });
}
const idle = { resetId: null, revision: 0, generation: 0, status: 'IDLE', message: '', completedSteps: [], retryable: false, remoteStatus: 'NOT_INTEGRATED' };

for (const failure of [401, 404, 503, '网络不可达']) {
  test(`状态查询失败（${failure}）仍能进入原生连接设置，但不能绕过屏障进入应用`, async () => {
    resetModules();
    const calls = nativeConnection();
    useFetchStub(() => {
      if (typeof failure === 'string') throw new Error(failure);
      return { ok: false, status: failure };
    });
    const React = getReact();
    const { AgentResetBoundary } = await loadModule('/src/components/reset/AgentResetBoundary.tsx');
    view = await mount(React.createElement(AgentResetBoundary, null, React.createElement('p', null, '业务应用哨兵')));
    assert.match(view.text(), new RegExp(String(failure)));
    assert.doesNotMatch(view.text(), /业务应用哨兵/);
    await view.click('后端连接设置');
    assert.match(view.text(), /连接配置/);
    assert.equal(view.queryAll('button').find(button => button.textContent === '配置连接').disabled, false);
    assert.doesNotMatch(view.text(), /业务应用哨兵/);
    await view.click('返回重置状态');
    assert.doesNotMatch(view.text(), /业务应用哨兵/);
    assert.equal(calls.some(call => call.command === 'restart_application'), false);
    assert.equal(calls.some(call => call.command === 'save_remote_backend_url'), false);
  });
}

test('连接恢复可修改地址与 Token；状态恢复不打断草稿，保存后仍须明确确认原生重启', async () => {
  resetModules();
  const calls = nativeConnection();
  const fetch = useFetchStub(() => ({ ok: false, status: 401 }));
  const React = getReact();
  const { AgentResetBoundary } = await loadModule('/src/components/reset/AgentResetBoundary.tsx');
  const { fetchAgentReset } = await loadModule('/src/services/agentResetService.ts');
  const { getApiBaseUrl } = await loadModule('/src/services/api.ts');
  const actualBackend = getApiBaseUrl();
  view = await mount(React.createElement(AgentResetBoundary, null, React.createElement('p', null, '业务应用哨兵')));
  await view.click('后端连接设置');
  await view.click('配置连接');
  await inputValue('#remote-backend-url', 'https://new.example.com');
  await inputValue('#remote-backend-token', 'fixture-only-api-token-'.repeat(2));
  fetch.respond(() => jsonResponse(idle));
  await act(async () => { await fetchAgentReset(); });
  assert.equal(document.querySelector('#remote-backend-url').value, 'https://new.example.com');
  assert.doesNotMatch(view.text(), /业务应用哨兵/);
  await clickDialog('保存配置');
  assert.deepEqual(calls.find(call => call.command === 'save_remote_backend_url').args,
    { remoteUrl: 'https://new.example.com', apiToken: 'fixture-only-api-token-'.repeat(2) });
  assert.match(document.querySelector('[role="dialog"]').textContent, /重启应用以切换后端/);
  assert.equal(calls.some(call => call.command === 'restart_application'), false);
  assert.equal(getApiBaseUrl(), actualBackend);
  await clickDialog('立即重启');
  assert.equal(calls.filter(call => call.command === 'restart_application').length, 1);
  assert.equal(calls.some(call => call.command === 'start_local_backend_fallback'), false);
});

test('浏览器连接失败提供开发后端指引，手动重查成功后才挂载应用', async () => {
  resetModules();
  const fetch = useFetchStub(() => ({ ok: false, status: 503 }));
  const React = getReact();
  const { AgentResetBoundary } = await loadModule('/src/components/reset/AgentResetBoundary.tsx');
  view = await mount(React.createElement(AgentResetBoundary, null, React.createElement('p', null, '业务应用哨兵')));
  assert.match(view.text(), /浏览器开发环境/);
  assert.doesNotMatch(view.text(), /业务应用哨兵/);
  assert.equal(view.queryAll('button').some(button => button.textContent === '后端连接设置'), false);
  fetch.respond(() => jsonResponse(idle));
  await view.click('重新检查状态');
  assert.equal(view.text(), '业务应用哨兵');
});
