import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { startHarness, stopHarness, loadModule } from '../../testing/harness.mjs';

let appendTextBlock,
  appendThinkingBlock,
  upsertToolCallBlock,
  applyToolResultBlock,
  formatToolGroupSummary,
  formatElapsedDuration,
  splitProcessAndFinalBlocks,
  groupTimelineBlocks,
  resolveToolActionLabel,
  mapTranscriptMessages;

before(async () => {
  await startHarness();
  ({
    appendTextBlock,
    appendThinkingBlock,
    upsertToolCallBlock,
    applyToolResultBlock,
    formatToolGroupSummary,
    formatElapsedDuration,
    splitProcessAndFinalBlocks,
    groupTimelineBlocks,
    resolveToolActionLabel,
  } = await loadModule('/src/features/chat/messageBlocks.ts'));
  ({ mapTranscriptMessages } = await loadModule('/src/features/chat/transcriptMessages.ts'));
});

after(stopHarness);

test('流式事件按真实发生顺序维护 ReAct 交错 blocks，同一轮工具归入同一组', () => {
  let msg = {
    id: 'a1',
    role: 'assistant',
    content: '',
    createdAt: 1000,
    startTime: 1000,
  };

  msg = appendThinkingBlock(msg, '先确定');
  msg = appendThinkingBlock(msg, '搜索关键词。');
  msg = appendTextBlock(msg, '\n\n');
  msg = appendTextBlock(msg, '我先搜一下');
  msg = appendTextBlock(msg, '今天的动态。');
  msg = upsertToolCallBlock(msg, { toolCallId: 'c1', toolName: 'web_search', command: '' });
  msg = upsertToolCallBlock(msg, { toolCallId: 'c1', toolName: 'web_search', command: '{"query":"AI"}' });
  msg = upsertToolCallBlock(msg, { toolCallId: 'c2', toolName: 'web_search', command: '{"query":"LLM"}' });
  msg = applyToolResultBlock(msg, { toolCallId: 'c1', result: 'r1', status: 'completed' });
  msg = applyToolResultBlock(msg, { toolCallId: 'c2', result: 'r2', status: 'completed' });

  msg = appendThinkingBlock(msg, '需要核对本地笔记。');
  msg = appendTextBlock(msg, '已经搜到新闻，我再对比一下记录。');
  msg = upsertToolCallBlock(msg, { toolCallId: 'c3', toolName: 'read_file', command: 'notes.md' });
  msg = applyToolResultBlock(msg, { toolCallId: 'c3', result: 'ok', status: 'completed' });
  msg = appendTextBlock(msg, '最终结论如下。');

  assert.equal(msg.blocks.length, 7);
  assert.deepEqual(
    msg.blocks.map((b) => b.kind),
    ['thinking', 'text', 'tool_group', 'thinking', 'text', 'tool_group', 'text'],
  );
  assert.equal(msg.blocks[0].content, '先确定搜索关键词。');
  assert.equal(msg.blocks[1].content, '我先搜一下今天的动态。');
  assert.equal(msg.blocks[2].tools.length, 2);
  assert.equal(msg.blocks[2].tools[0].command, '{"query":"AI"}');
  assert.equal(msg.blocks[2].tools[0].output, 'r1');
  assert.equal(msg.blocks[4].content, '已经搜到新闻，我再对比一下记录。');
  assert.equal(msg.blocks[5].tools.length, 1);
  assert.equal(msg.blocks[6].content, '最终结论如下。');
});

test('formatToolGroupSummary、formatElapsedDuration 与 splitProcessAndFinalBlocks 行为准确', () => {
  assert.equal(
    formatToolGroupSummary(
      [
        { toolCallId: '1', toolName: 'web_search', command: 'a', status: 'running' },
        { toolCallId: '2', toolName: 'web_search', command: 'b', status: 'completed' },
      ],
      true,
    ).label,
    '正在网页搜索（2 项）',
  );

  assert.equal(
    formatToolGroupSummary(
      [
        { toolCallId: '1', toolName: 'web_search', command: 'a', status: 'completed' },
        { toolCallId: '2', toolName: 'web_search', command: 'b', status: 'completed' },
        { toolCallId: '3', toolName: 'web_search', command: 'c', status: 'completed' },
      ],
      false,
    ).label,
    '已网页搜索（3 次）',
  );

  assert.equal(
    formatToolGroupSummary(
      [
        { toolCallId: '1', toolName: 'reset_equipped_tools', command: '', status: 'completed' },
        { toolCallId: '2', toolName: 'web_search', command: 'a', status: 'completed' },
        { toolCallId: '3', toolName: 'web_search', command: 'b', status: 'completed' },
      ],
      false,
    ).label,
    '已装载工具能力、网页搜索（3 项）',
  );

  assert.equal(
    formatToolGroupSummary(
      [
        { toolCallId: '1', toolName: 'read_file', command: 'a.ts', status: 'completed' },
        { toolCallId: '2', toolName: 'execute_command', command: 'pnpm test', status: 'failed' },
      ],
      false,
    ).label,
    '已读取文件、运行命令（2 项） · 1 项失败',
  );

  assert.equal(formatElapsedDuration(18), '用时 18 秒');
  assert.equal(formatElapsedDuration(1578), '用时 26分钟 18秒');
  assert.equal(formatElapsedDuration(120), '用时 2分钟');

  const split = splitProcessAndFinalBlocks([
    { id: 'b0', kind: 'thinking', content: 't' },
    { id: 'b1', kind: 'tool_group', tools: [{ toolCallId: '1', toolName: 'web_search', command: 'q', status: 'completed' }] },
    { id: 'b2', kind: 'text', content: '最终结论' },
  ]);
  assert.equal(split.processBlocks.length, 2);
  assert.equal(split.finalTextBlock?.content, '最终结论');
});

test('mapTranscriptMessages 兼容带 blocks 的新记录与无 blocks 的旧记录', () => {
  const [withBlocks, legacy] = mapTranscriptMessages([
    {
      id: 'm1',
      turnId: 't1',
      role: 'ASSISTANT',
      content: '最终总结',
      createdAt: '2026-11-17T01:00:00Z',
      status: 'COMPLETED',
      durationMillis: 2400,
      tools: [{ toolCallId: 'c1', toolName: 'web_search', command: 'q', output: 'ok', status: 'COMPLETED' }],
      thinking: '思考内容',
      blocks: [
        { kind: 'THINKING', content: '思考内容', tools: [] },
        { kind: 'TEXT', content: '先检索资料。', tools: [] },
        {
          kind: 'TOOL_GROUP',
          content: '',
          tools: [{ toolCallId: 'c1', toolName: 'web_search', command: 'q', output: 'ok', status: 'COMPLETED' }],
        },
        { kind: 'TEXT', content: '最终总结', tools: [] },
      ],
    },
    {
      id: 'm2',
      turnId: 't2',
      role: 'ASSISTANT',
      content: '旧版回复',
      createdAt: '2026-11-16T01:00:00Z',
      status: 'COMPLETED',
      durationMillis: 1200,
      tools: [],
      thinking: '旧思考',
    },
  ]);

  assert.equal(withBlocks.blocks.length, 4);
  assert.equal(withBlocks.blocks[2].kind, 'tool_group');
  assert.equal(withBlocks.blocks[2].tools[0].status, 'completed');
  assert.equal(legacy.blocks, undefined);
  assert.equal(legacy.reasoning, '旧思考');
});

test('groupTimelineBlocks 将两段阶段正文间的连续思考与工具合并为单个活动条，并识别工具后分析状态', () => {
  const raw = [
    { id: 'b0', kind: 'thinking', content: '先梳理需求。' },
    { id: 'b1', kind: 'text', content: '我先排查后端配置，再处理前端页面。' },
    { id: 'b2', kind: 'thinking', content: '读取配置文件' },
    {
      id: 'b3',
      kind: 'tool_group',
      tools: [{ toolCallId: 't1', toolName: 'readFile', command: 'a.java', status: 'completed' }],
    },
    { id: 'b4', kind: 'thinking', content: '再编辑并跑测试' },
    {
      id: 'b5',
      kind: 'tool_group',
      tools: [
        { toolCallId: 't2', toolName: 'editFile', command: 'a.java', status: 'completed' },
        { toolCallId: 't3', toolName: 'custom_bash', command: 'mvn test', status: 'completed' },
      ],
    },
    { id: 'b6', kind: 'thinking', content: '测试通过，准备同步阶段结论' },
    { id: 'b7', kind: 'text', content: '后端已完成并通过单测，接下来改前端展示。' },
    {
      id: 'b8',
      kind: 'tool_group',
      tools: [{ toolCallId: 't4', toolName: 'grepFiles', command: 'AgentResponse', status: 'completed' }],
    },
    { id: 'b9', kind: 'thinking', content: '分析 grep 结果' },
  ];

  const grouped = groupTimelineBlocks(raw);
  assert.deepEqual(
    grouped.map((b) => b.kind),
    ['thinking', 'text', 'tool_group', 'text', 'tool_group'],
  );
  assert.equal(grouped[2].tools.length, 3);
  assert.equal(grouped[2].analyzingAfterTools, true);
  assert.equal(grouped[4].tools.length, 1);
  assert.equal(grouped[4].analyzingAfterTools, true);

  assert.equal(
    formatToolGroupSummary(grouped[2].tools, false).label,
    '已读取文件、编辑文件、运行命令（3 项）',
  );
  const analyzing = formatToolGroupSummary(grouped[4].tools, true, { analyzingAfterTools: true });
  assert.equal(analyzing.label, '已搜索内容 · 正在分析结果…');
  assert.equal(analyzing.running, true);

  assert.equal(resolveToolActionLabel('readFile'), '读取文件');
  assert.equal(resolveToolActionLabel('calendar_create'), '操作日历');
  assert.equal(resolveToolActionLabel('study_start'), '记录学习');
});
