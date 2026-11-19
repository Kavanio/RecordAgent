import assert from 'node:assert/strict';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { act, getReact, jsonResponse, loadModule, mount, startHarness, stopHarness, useFetchStub } from '../../testing/harness.mjs';

let Section, Provider, view, stub, statusData, checkData, failUpdate, writes;

before(async () => {
  await startHarness();
  ({ DesktopControlSettingsSection: Section } = await loadModule('/src/components/settings/DesktopControlSettingsSection.tsx'));
  ({ MessageProvider: Provider } = await loadModule('/src/components/common/Message.tsx'));
});

after(stopHarness);

beforeEach(() => {
  statusData = { enabled: false, cliPath: '', hostOs: 'windows' };
  checkData = {
    available: true,
    version: '1.3.1',
    platform: 'windows',
    accessibilityState: 'granted',
    screenRecordingState: 'granted',
    message: '已检测到 agent-desktop v1.3.1（windows），系统无障碍权限已就绪。',
    suggestion: '',
  };
  failUpdate = false;
  writes = [];
  stub = useFetchStub(async (url, init) => {
    const method = init?.method ?? 'GET';
    if (url.pathname === '/agent/desktop-control' && method === 'GET') {
      return jsonResponse(statusData);
    }
    if (url.pathname === '/agent/desktop-control' && method === 'PUT') {
      const body = JSON.parse(init.body);
      writes.push(body);
      if (failUpdate) {
        return new Response(JSON.stringify({ code: 500, message: '磁盘写入失败' }), {
          status: 500,
          headers: { 'Content-Type': 'application/json' },
        });
      }
      statusData = {
        ...statusData,
        enabled: body.enabled,
        cliPath: body.cliPath === undefined ? statusData.cliPath : body.cliPath,
      };
      return jsonResponse(statusData);
    }
    if (url.pathname === '/agent/desktop-control/check' && method === 'GET') {
      return jsonResponse(checkData);
    }
    return jsonResponse(null, 404);
  });
});

afterEach(async () => {
  await view?.unmount();
  stub.restore();
});

async function render() {
  const React = getReact();
  view = await mount(React.createElement(Provider, null, React.createElement(Section)));
}

async function clickButton(text) {
  const button = [...document.querySelectorAll('button')].find((node) => node.textContent === text);
  assert.ok(button, `找不到按钮：${text}`);
  await act(async () => button.click());
}

test('读取后端配置并展示主机平台，切换开关可写回 config.json', async () => {
  await render();
  assert.match(view.text(), /当前后端主机：Windows/);
  const toggle = document.querySelector('input[type="checkbox"]');
  assert.ok(toggle);
  assert.equal(toggle.checked, false);

  await act(async () => toggle.click());
  assert.deepEqual(writes[0], { enabled: true });
  assert.equal(toggle.checked, true);
});

test('保存失败保持原开关状态并提示错误', async () => {
  failUpdate = true;
  await render();
  const toggle = document.querySelector('input[type="checkbox"]');
  await act(async () => toggle.click());
  assert.equal(toggle.checked, false);
  assert.match(document.body.textContent, /磁盘写入失败/);
});

test('支持保存自定义 CLI 路径与手动检测运行环境', async () => {
  await render();
  const input = document.querySelector('#desktop-control-cli-path');
  assert.ok(input);
  await act(async () => {
    Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set.call(input, 'C:\\tools\\agent-desktop.exe');
    input.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await clickButton('保存路径');
  assert.deepEqual(writes[0], { enabled: false, cliPath: 'C:\\tools\\agent-desktop.exe' });

  await clickButton('检测环境');
  assert.match(view.text(), /已就绪/);
  assert.match(view.text(), /v1\.3\.1/);
});
