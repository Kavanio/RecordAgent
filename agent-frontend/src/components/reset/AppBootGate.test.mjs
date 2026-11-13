import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, getReact, mount, useFetchStub, jsonResponse, deferred, act, resetModules } from '../../testing/harness.mjs';

let view, stub;
const idle = { resetId: null, revision: 0, generation: 0, status: 'IDLE', message: '', completedSteps: [], retryable: false, remoteStatus: 'NOT_INTEGRATED' };

before(() => startHarness());
after(() => stopHarness());
afterEach(async () => {
  await view?.unmount();
  view = null;
  stub?.restore();
  stub = null;
  delete window.__TAURI_INTERNALS__;
});

test('首帧立即展示连续的大树启动动画，跨越连接初始化、重置检查与配置准备阶段且不重置动画', async () => {
  resetModules();
  const portGate = deferred();
  const resetGate = deferred();
  const configGate = deferred();

  window.__TAURI_INTERNALS__ = {
    invoke: async (command) => {
      if (command === 'load_desktop_preferences') return {};
      if (command === 'get_backend_mode') return 'sidecar';
      if (command === 'get_backend_port') return portGate.promise;
      if (command === 'get_backend_connection') return { mode: 'sidecar', remoteUrl: null, savedRemoteUrl: null, hasApiToken: false, restartRequired: false };
      throw new Error(`未处理原生命令：${command}`);
    },
  };
  stub = useFetchStub(() => resetGate.promise);

  const React = getReact();
  const { AppBootGate } = await loadModule('/src/components/reset/AppBootGate.tsx');
  const { AgentResetBoundary } = await loadModule('/src/components/reset/AgentResetBoundary.tsx');
  const { useBootSplashHold } = await loadModule('/src/components/reset/bootSplashContext.ts');

  function FakePrimaryApp() {
    const [ready, setReady] = React.useState(false);
    useBootSplashHold('primary-app', !ready);
    React.useEffect(() => {
      void configGate.promise.then(() => setReady(true));
    }, []);
    return ready ? React.createElement('p', null, '主界面已就绪') : null;
  }

  view = await mount(
    React.createElement(
      AppBootGate,
      null,
      React.createElement(AgentResetBoundary, null, React.createElement(FakePrimaryApp)),
    ),
  );

  // 阶段 1：等待后端端口期间立即有动画，无白屏，也不闪现重置界面
  const initialSvg = view.query('svg');
  assert.ok(initialSvg);
  assert.match(view.text(), /正在启动 ButvanAgent/);
  assert.doesNotMatch(view.text(), /Agent 恢复出厂设置/);
  assert.doesNotMatch(view.text(), /主界面已就绪/);

  // 阶段 2：后端端口就绪，进入重置状态检查，仍然保留同一颗大树 DOM 实例
  await act(async () => {
    portGate.resolve(18081);
  });
  assert.equal(view.query('svg'), initialSvg);
  assert.doesNotMatch(view.text(), /Agent 恢复出厂设置/);

  // 阶段 3：重置状态返回 IDLE，挂载应用配置检查，依然保持同一颗大树 DOM 实例
  await act(async () => {
    resetGate.resolve(jsonResponse(idle));
  });
  assert.equal(view.query('svg'), initialSvg);
  assert.doesNotMatch(view.text(), /主界面已就绪/);

  // 阶段 4：配置就绪后显示主界面，并在淡出结束后卸载遮罩
  await act(async () => {
    configGate.resolve();
  });
  assert.match(view.text(), /主界面已就绪/);
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 260));
  });
  assert.equal(view.query('svg'), null);
  assert.equal(view.text(), '主界面已就绪');
});

test('当重置屏障进入错误或阻塞态时，启动遮罩立即让位显示恢复画面', async () => {
  resetModules();
  const resetGate = deferred();
  stub = useFetchStub(() => resetGate.promise);

  const React = getReact();
  const { AppBootGate } = await loadModule('/src/components/reset/AppBootGate.tsx');
  const { AgentResetBoundary } = await loadModule('/src/components/reset/AgentResetBoundary.tsx');

  view = await mount(
    React.createElement(
      AppBootGate,
      null,
      React.createElement(AgentResetBoundary, null, React.createElement('p', null, '业务应用哨兵')),
    ),
  );

  assert.match(view.text(), /正在启动 ButvanAgent/);
  await act(async () => {
    resetGate.resolve({ ok: false, status: 503 });
  });
  assert.match(view.text(), /Agent 恢复出厂设置/);
  assert.match(view.text(), /503/);
  assert.doesNotMatch(view.text(), /业务应用哨兵/);
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 260));
  });
  assert.equal(view.query('[role="status"]')?.textContent.includes('503') || view.text().includes('503'), true);
});

test('后端连接初始化异常时不陷入永久白屏，展示错误恢复与重试', async () => {
  resetModules();
  let failOnce = true;
  window.__TAURI_INTERNALS__ = {
    invoke: async (command) => {
      if (command === 'load_desktop_preferences') return {};
      if (command === 'get_backend_mode') {
        if (failOnce) {
          failOnce = false;
          throw new Error('原生连接读取超时');
        }
        return 'dev';
      }
      if (command === 'get_backend_connection') {
        return { mode: 'dev', remoteUrl: null, savedRemoteUrl: null, hasApiToken: false, restartRequired: false };
      }
      throw new Error(`未处理原生命令：${command}`);
    },
  };
  stub = useFetchStub(() => jsonResponse(idle));

  const React = getReact();
  const { AppBootGate } = await loadModule('/src/components/reset/AppBootGate.tsx');
  const { AgentResetBoundary } = await loadModule('/src/components/reset/AgentResetBoundary.tsx');

  view = await mount(
    React.createElement(
      AppBootGate,
      null,
      React.createElement(AgentResetBoundary, null, React.createElement('p', null, '恢复后主界面')),
    ),
  );

  assert.match(view.text(), /应用启动遇到问题/);
  assert.match(view.text(), /原生连接读取超时/);
  await view.click('后端连接设置');
  assert.match(view.text(), /连接配置/);
  await view.click('返回启动检查');
  await view.click('重试启动');
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 260));
  });
  assert.equal(view.text(), '恢复后主界面');
});
