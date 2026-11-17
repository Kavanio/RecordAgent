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

test('交错时间线按顺序渲染正文与默认折叠的工具组和思考块，支持手动点击展开', async () => {
  const msg = {
    id: 'msg-interleaved',
    role: 'assistant',
    content: '最终结论如下。',
    createdAt: 1000,
    elapsedTime: 6,
    status: 'COMPLETED',
    blocks: [
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
            status: 'completed',
          },
          {
            toolCallId: 'c2',
            toolName: 'web_search',
            command: '{"query":"LLM"}',
            output: 'result 2',
            status: 'completed',
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
    ],
  };

  view = await mount(React.createElement(AssistantMessageItem, {
    msg,
    tokenUsageActive: false,
    onOpenTokenUsage() {},
  }));

  const fullText = document.body.textContent || '';
  const idxFirstText = fullText.indexOf('我先搜一下今天的动态。');
  const idxFirstGroup = fullText.indexOf('已网页搜索（2 次）');
  const idxSecondText = fullText.indexOf('已经确认几条线索，继续读取本地笔记。');
  const idxSecondGroup = fullText.indexOf('已读取文件');
  const idxFinalText = fullText.indexOf('最终结论如下。');

  assert.ok(idxFirstText >= 0);
  assert.ok(idxFirstGroup > idxFirstText);
  assert.ok(idxSecondText > idxFirstGroup);
  assert.ok(idxSecondGroup > idxSecondText);
  assert.ok(idxFinalText > idxSecondGroup);

  const thinkingTrigger = [...document.querySelectorAll('button')]
    .find((btn) => btn.textContent?.includes('思考 6s'));
  assert.ok(thinkingTrigger);
  assert.equal(thinkingTrigger.getAttribute('aria-expanded'), 'false');

  const searchGroupTrigger = [...document.querySelectorAll('button')]
    .find((btn) => btn.textContent?.includes('已网页搜索（2 次）'));
  assert.ok(searchGroupTrigger);
  assert.equal(searchGroupTrigger.getAttribute('aria-expanded'), 'false');

  searchGroupTrigger.click();
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(searchGroupTrigger.getAttribute('aria-expanded'), 'true');
});

test('流式思考块与旧格式折叠面板同样默认收起并支持手动展开', async () => {
  const streamingMsg = {
    id: 'msg-streaming',
    role: 'assistant',
    content: '',
    createdAt: Date.now(),
    startTime: Date.now(),
    blocks: [
      { id: 'b0', kind: 'thinking', content: '正在分析参数。' },
    ],
  };

  view = await mount(React.createElement(AssistantMessageItem, {
    msg: streamingMsg,
    tokenUsageActive: false,
    onOpenTokenUsage() {},
  }));

  const streamingThinkingBtn = [...document.querySelectorAll('button')]
    .find((btn) => btn.textContent?.includes('思考中…'));
  assert.ok(streamingThinkingBtn);
  assert.equal(streamingThinkingBtn.disabled, false);
  assert.equal(streamingThinkingBtn.getAttribute('aria-expanded'), 'false');

  streamingThinkingBtn.click();
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(streamingThinkingBtn.getAttribute('aria-expanded'), 'true');

  await view.unmount();

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
    .find((btn) => btn.textContent?.includes('共耗时 3 秒'));
  assert.ok(legacyTrigger);
  assert.equal(legacyTrigger.getAttribute('aria-expanded'), 'false');
});
