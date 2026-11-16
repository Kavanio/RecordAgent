import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount, useFetchStub, jsonResponse, act } from '../../testing/harness.mjs';

let React, useCompanionNotifications, host, stub, originalEventSource;

before(async () => {
  ({ react: React } = await startHarness());
  ({ useCompanionNotifications } = await loadModule('/src/components/review/useCompanionNotifications.ts'));
});

after(stopHarness);

afterEach(async () => {
  await host?.unmount();
  host = null;
  stub?.restore();
  if (originalEventSource !== undefined) {
    globalThis.EventSource = originalEventSource;
    window.EventSource = originalEventSource;
    originalEventSource = undefined;
  }
});

test('通过 SSE 订阅接收桌面主动问候、完成 ack 并支持点击去回复切换桌面会话', async () => {
  const calls = [];
  const activeSessions = [];
  const messagesShown = [];

  stub = useFetchStub(async (url, init) => {
    calls.push([url.pathname, init?.method || 'GET']);
    if (url.pathname.includes('/desktop-notices/') && url.pathname.endsWith('/ack')) {
      return jsonResponse(null);
    }
    if (url.pathname.endsWith('/sessions/desktop-main-1')) {
      return jsonResponse({
        summary: { id: 'desktop-main-1', title: '日常陪伴', lastMessagePreview: '晚安问候' },
        messages: [{ id: 'm1', role: 'ASSISTANT', content: '晚上忙完了吗？', status: 'COMPLETED', createdAt: '2026-11-13T12:00:00Z' }],
      });
    }
    return jsonResponse(null);
  });

  const listeners = new Map();
  originalEventSource = globalThis.EventSource;
  class FakeEventSource {
    addEventListener(type, fn) {
      listeners.set(type, fn);
    }
    close() {}
  }
  globalThis.EventSource = FakeEventSource;
  window.EventSource = FakeEventSource;

  function Harness() {
    useCompanionNotifications({
      showMessage: (type, content, opts) => messagesShown.push({ type, content, opts }),
      setIsSettingsOpen: () => {},
      setActiveFeature: () => {},
      setActiveSessionId: id => activeSessions.push(id),
      fetchSessionDetail: async () => ({
        summary: { id: 'desktop-main-1', title: '日常陪伴', lastMessagePreview: '晚上忙完了吗？' },
        messages: [],
      }),
      setSessions: () => {},
    });
    return React.createElement('div', null, 'ready');
  }

  host = await mount(React.createElement(Harness));
  const onSnapshot = listeners.get('snapshot');
  assert.ok(onSnapshot, '应建立 /companion/stream 的 snapshot 事件监听');

  await act(async () => {
    onSnapshot({
      data: JSON.stringify({
        pendingDesktopNotices: [{
          id: 'notice-1',
          sessionId: 'desktop-main-1',
          kind: 'COMPANION_OUTREACH',
          title: 'Agent 主动问候',
          content: '晚上忙完了吗？',
          createdAt: '2026-11-13T12:00:00Z',
          acknowledged: false,
        }],
      }),
    });
  });

  assert.equal(calls.filter(([p, m]) => p.endsWith('/ack') && m === 'POST').length, 1);
  assert.equal(messagesShown.length, 1);
  assert.match(messagesShown[0].content, /晚上忙完了吗/);

  await act(async () => {
    messagesShown[0].opts.action.onClick();
  });
  assert.deepEqual(activeSessions, ['desktop-main-1']);
});
