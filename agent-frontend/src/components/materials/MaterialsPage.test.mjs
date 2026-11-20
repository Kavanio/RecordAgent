import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import {
  startHarness,
  stopHarness,
  loadModule,
  mount,
  getReact,
  useFetchStub,
  jsonResponse,
  act,
} from '../../testing/harness.mjs';

let React, MaterialsPage, MessageProvider, stub;

const SAMPLE_ITEM = {
  id: '0123456789abcdef0123456789abcdef',
  fileId: '0123456789abcdef0123456789abcdef',
  channel: 'DESKTOP',
  sessionKind: 'GENERAL',
  sessionId: 'session-1',
  projectId: null,
  sessionTitle: '接口联调排查',
  turnId: 'turn-1',
  category: 'IMAGE',
  subType: 'PNG',
  originalName: 'chat-image-2026-11-17-1.png',
  mediaType: 'image/png',
  sizeBytes: 2048,
  sha256: 'deadbeef',
  archivePath: 'desktop/general/session-1/images/png/2026-11/2026-11-17/0123456789abcdef0123456789abcdef.png',
  createdDate: '2026-11-17',
  createdAt: '2026-11-17T12:00:00Z',
};

async function flushUi() {
  for (let i = 0; i < 4; i += 1) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
  }
}

before(async () => {
  await startHarness();
  React = getReact();
  ({ MaterialsPage } = await loadModule('/src/components/materials/MaterialsPage.tsx'));
  ({ MessageProvider } = await loadModule('/src/components/common/Message.tsx'));
});

after(stopHarness);

test('档案页加载归档素材并支持查看详情与删除', async () => {
  let deletedId = null;
  let items = [SAMPLE_ITEM];
  stub = useFetchStub((url, init = {}) => {
    const pathname = url.pathname;
    if (pathname === '/agent/materials/summary') {
      return jsonResponse({
        totalCount: items.length,
        totalBytes: items.reduce((acc, cur) => acc + cur.sizeBytes, 0),
        byCategory: { IMAGE: items.length },
        byChannel: { DESKTOP: items.length },
        bySessionKind: { GENERAL: items.length },
        bySubType: { PNG: items.length },
        sessions: items.length
          ? [{
              sessionId: 'session-1',
              sessionTitle: '接口联调排查',
              channel: 'DESKTOP',
              sessionKind: 'GENERAL',
              count: 1,
              latestCreatedAt: '2026-11-17T12:00:00Z',
            }]
          : [],
      });
    }
    if (pathname === '/agent/materials') {
      return jsonResponse({ items, total: items.length });
    }
    if (pathname === `/agent/materials/${SAMPLE_ITEM.fileId}` && init.method === 'DELETE') {
      deletedId = SAMPLE_ITEM.fileId;
      items = [];
      return jsonResponse('档案素材已删除');
    }
    throw new Error(`未预期的请求: ${pathname}`);
  });

  const openedSessions = [];
  const host = await mount(
    React.createElement(
      MessageProvider,
      null,
      React.createElement(MaterialsPage, {
        existingSessionIds: new Set(['session-1']),
        onOpenSession: (id) => openedSessions.push(id),
      }),
    ),
  );

  try {
    await flushUi();
    assert.match(host.text(), /chat-image-2026-11-17-1\.png/);
    assert.match(host.text(), /接口联调排查/);
    assert.match(host.text(), /11月17日/);
    assert.match(host.text(), /1 项/);

    // 切换列表视图并切回网格视图
    const toggleViewBtn = document.body.querySelector('button[aria-label="切换列表视图"]');
    assert.ok(toggleViewBtn);
    await act(async () => toggleViewBtn.click());
    await flushUi();
    assert.match(host.text(), /PNG/);
    assert.ok(document.body.querySelector('button[aria-label="切换网格视图"]'));

    await host.click(SAMPLE_ITEM.originalName);
    await flushUi();
    assert.match(document.body.textContent ?? '', new RegExp(SAMPLE_ITEM.fileId));
    assert.match(document.body.textContent ?? '', /materials\/desktop\/general\/session-1/);

    const deleteBtn = [...document.body.querySelectorAll('button')].find((b) => b.textContent?.includes('删除档案'));
    assert.ok(deleteBtn);
    await act(async () => deleteBtn.click());
    await flushUi();

    const confirmBtn = [...document.body.querySelectorAll('button')].find((b) => b.textContent?.includes('永久删除'));
    assert.ok(confirmBtn);
    await act(async () => confirmBtn.click());
    await flushUi();

    assert.equal(deletedId, SAMPLE_ITEM.fileId);
    assert.match(host.text(), /暂无已保存的对话档案/);
    assert.equal(openedSessions.length, 0);
  } finally {
    await host.unmount();
    stub?.restore();
  }
});
