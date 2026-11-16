import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount } from '../../testing/harness.mjs';

let React, AssistantMessageItem, mapTranscriptMessages, view;

before(async () => {
  ({ react: React } = await startHarness());
  ({ AssistantMessageItem } = await loadModule('/src/components/chat/AssistantMessageItem.tsx'));
  ({ mapTranscriptMessages } = await loadModule('/src/features/chat/transcriptMessages.ts'));
});

after(stopHarness);

afterEach(async () => {
  await view?.unmount();
  view = null;
});

function expectedLocalSecondTime(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

test('主动推送消息底部渲染精确到秒的时间，普通对话回复不渲染该时间', async () => {
  const proactiveIso = '2026-11-16T00:40:06.250Z';
  const legacyProactiveIso = '2026-11-15T13:05:09.000Z';
  const mapped = mapTranscriptMessages([
    {
      id: 'p-1',
      turnId: 'turn-p1',
      role: 'ASSISTANT',
      content: '看了一眼外面的天气，合肥当前转为雾。',
      createdAt: proactiveIso,
      status: 'COMPLETED',
      durationMillis: 0,
      tools: [],
      thinking: null,
      usage: null,
      proactive: true,
    },
    {
      id: 'p-legacy',
      turnId: 'turn-p-legacy',
      role: 'ASSISTANT',
      content: '跟你顺手理一下这一天的复盘（2026-11-14）：',
      createdAt: legacyProactiveIso,
      status: 'COMPLETED',
      durationMillis: 0,
      tools: [],
      thinking: null,
      usage: null,
    },
    {
      id: 'u-1',
      turnId: 'turn-u1',
      role: 'USER',
      content: '好的谢谢',
      createdAt: '2026-11-16T00:41:00.000Z',
      status: 'COMPLETED',
      tools: [],
    },
    {
      id: 'a-1',
      turnId: 'turn-u1',
      role: 'ASSISTANT',
      content: '不客气，有需要随时叫我。',
      createdAt: '2026-11-16T00:41:05.000Z',
      status: 'COMPLETED',
      durationMillis: 0,
      tools: [],
      thinking: null,
      usage: null,
    },
    {
      id: 'p-bad-time',
      turnId: 'turn-p-bad',
      role: 'ASSISTANT',
      content: '无效时间测试',
      createdAt: '',
      status: 'COMPLETED',
      durationMillis: 0,
      tools: [],
      proactive: true,
    },
  ]);

  view = await mount(React.createElement(
    'div',
    null,
    ...mapped
      .filter((msg) => msg.role === 'assistant')
      .map((msg) => React.createElement(AssistantMessageItem, {
        key: msg.id,
        msg,
        tokenUsageActive: false,
        onOpenTokenUsage() {},
      })),
  ));

  const timeNodes = [...document.querySelectorAll('time')];
  assert.equal(timeNodes.length, 2);
  assert.equal(timeNodes[0].textContent, expectedLocalSecondTime(proactiveIso));
  assert.equal(timeNodes[1].textContent, expectedLocalSecondTime(legacyProactiveIso));
});
