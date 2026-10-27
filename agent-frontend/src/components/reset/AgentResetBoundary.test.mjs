import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, act, resetModules } from '../../testing/harness.mjs';
let view;
before(() => startHarness());
after(() => stopHarness());
afterEach(async () => { await view?.unmount(); view = null; });
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
