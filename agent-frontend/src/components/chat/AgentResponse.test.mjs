import assert from 'node:assert/strict';
import { before, after, afterEach, test } from 'node:test';
import { startHarness, stopHarness, loadModule, mount } from '../../testing/harness.mjs';

let React, AssistantMessageItem, view;

before(async () => {
  ({ react: React } = await startHarness());
  ({ AssistantMessageItem } = await loadModule('/src/components/chat/AssistantMessageItem.tsx'));
});

after(stopHarness);

afterEach(async () => {
  await view?.unmount();
  view = null;
});

function buildMultiRoundBlocks(toolStatus = 'completed') {
  return [
    { id: 'b0', kind: 'thinking', content: '先搜索今天的新闻。' },
    { id: 'b1', kind: 'text', content: '我先搜一下今天的动态。' },
    {
      id: 'b2',
      kind: 'tool_group',
      tools: [
        {
          toolCallId: 'c1',
          toolName: 'web_search',
          command: '{"query":"AI news"}',
          output: 'result 1',
          status: toolStatus,
        },
        {
          toolCallId: 'c2',
          toolName: 'web_search',
          command: '{"query":"LLM"}',
          output: 'result 2',
          status: toolStatus,
        },
      ],
    },
    { id: 'b3', kind: 'text', content: '已经确认几条线索，继续读取本地笔记。' },
    {
      id: 'b4',
      kind: 'tool_group',
      tools: [
        {
          toolCallId: 'c3',
          toolName: 'read_file',
          command: 'notes.md',
          output: 'note body',
          status: 'completed',
        },
      ],
    },
    { id: 'b5', kind: 'text', content: '最终结论如下。' },
  ];
}

test('进行中实时按 ReAct 交错顺序展示，且思考块与工具组默认折叠可手动展开', async () => {
  const streamingMsg = {
    id: 'msg-streaming-react',
    role: 'assistant',
    content: '我先搜一下今天的动态。已经确认几条线索，继续读取本地笔记。最终结论如下。',
    createdAt: Date.now(),
    startTime: Date.now(),
    blocks: buildMultiRoundBlocks('running'),
  };

  view = await mount(React.createElement(AssistantMessageItem, {
    msg: streamingMsg,
    tokenUsageActive: false,
    onOpenTokenUsage() {},
  }));

  const fullText = document.body.textContent || '';
  const idxFirstText = fullText.indexOf('我先搜一下今天的动态。');
  const idxFirstGroup = fullText.indexOf('正在网页搜索（2 项）');
  const idxSecondText = fullText.indexOf('已经确认几条线索，继续读取本地笔记。');
  const idxSecondGroup = fullText.indexOf('已读取文件');
  const idxFinalText = fullText.indexOf('最终结论如下。');

  assert.ok(idxFirstText >= 0);
  assert.ok(idxFirstGroup > idxFirstText);
  assert.ok(idxSecondText > idxFirstGroup);
  assert.ok(idxSecondGroup > idxSecondText);
  assert.ok(idxFinalText > idxSecondGroup);

  const thinkingTrigger = [...document.querySelectorAll('button')]
    .find((btn) => /思考 \d+s/.test(btn.textContent || '') || btn.textContent?.includes('已思考'));
  assert.ok(thinkingTrigger);
  assert.equal(thinkingTrigger.getAttribute('aria-expanded'), 'false');

  const searchGroupTrigger = [...document.querySelectorAll('button')]
    .find((btn) => btn.textContent?.includes('正在网页搜索（2 项）'));
  assert.ok(searchGroupTrigger);
  assert.equal(searchGroupTrigger.getAttribute('aria-expanded'), 'false');

  await React.act(async () => {
    searchGroupTrigger.click();
  });
  assert.equal(searchGroupTrigger.getAttribute('aria-expanded'), 'true');
});

test('任务完成后前置思考与工具链统一折叠为总耗时，点击可展开查看完整交错过程', async () => {
  const completedMsg = {
    id: 'msg-completed-react',
    role: 'assistant',
    content: '最终结论如下。',
    createdAt: 1000,
    elapsedTime: 1578,
    status: 'COMPLETED',
    blocks: buildMultiRoundBlocks('completed'),
  };

  view = await mount(React.createElement(AssistantMessageItem, {
    msg: completedMsg,
    tokenUsageActive: false,
    onOpenTokenUsage() {},
  }));

  const summaryTrigger = [...document.querySelectorAll('button')]
    .find((btn) => btn.textContent?.includes('用时 26分钟 18秒'));
  assert.ok(summaryTrigger);
  assert.equal(summaryTrigger.getAttribute('aria-expanded'), 'false');

  const fullText = document.body.textContent || '';
  assert.ok(fullText.includes('最终结论如下。'));
  assert.ok(fullText.indexOf('用时 26分钟 18秒') < fullText.indexOf('最终结论如下。'));

  await React.act(async () => {
    summaryTrigger.click();
  });
  assert.equal(summaryTrigger.getAttribute('aria-expanded'), 'true');

  const searchGroupTrigger = [...document.querySelectorAll('button')]
    .find((btn) => btn.textContent?.includes('已网页搜索（2 次）'));
  assert.ok(searchGroupTrigger);
  assert.equal(searchGroupTrigger.getAttribute('aria-expanded'), 'false');

  await React.act(async () => {
    searchGroupTrigger.click();
  });
  assert.equal(searchGroupTrigger.getAttribute('aria-expanded'), 'true');
});

test('旧格式折叠面板同样默认收起并展示统一用时文案', async () => {
  const legacyMsg = {
    id: 'msg-legacy',
    role: 'assistant',
    content: '旧格式答复',
    reasoning: '旧思考链。',
    tools: [{ toolCallId: 't1', toolName: 'read_file', command: 'a.txt', status: 'completed', output: 'hi' }],
    createdAt: 1000,
    elapsedTime: 3,
    status: 'COMPLETED',
  };

  view = await mount(React.createElement(AssistantMessageItem, {
    msg: legacyMsg,
    tokenUsageActive: false,
    onOpenTokenUsage() {},
  }));

  const legacyTrigger = [...document.querySelectorAll('button')]
    .find((btn) => btn.textContent?.includes('用时 3 秒'));
  assert.ok(legacyTrigger);
  assert.equal(legacyTrigger.getAttribute('aria-expanded'), 'false');
});
