import type { ChatMessage, MessageBlock, ToolExecution, TraceNode } from '../../types/chat';

const TOOL_ACTION_LABELS: Record<string, string> = {
  read_file: '读取文件',
  write_file: '写入文件',
  edit_file: '编辑文件',
  list_dir: '查看目录',
  list_files: '查看目录',
  search_files: '搜索文件',
  grep_search: '搜索内容',
  glob: '查找文件',
  execute_command: '运行命令',
  execute: '运行命令',
  terminal: '运行命令',
  web_search: '网页搜索',
  search_web: '网页搜索',
  web_fetch: '读取网页',
  fetch_url: '读取网页',
  query_database: 'SQL 查询',
  method_search: '检索方法卡',
  reset_equipped_tools: '装载工具能力',
  custom_bash: '运行命令',
  acceptance_report: '生成验收报告',
  session_recent: '查看近期会话',
  session_list: '列举会话',
  session_history: '读取历史对话',
  session_search: '搜索历史对话',
};

function createBlockId(msg: ChatMessage, blocks: MessageBlock[]): string {
  return `${msg.id}_b_${blocks.length}`;
}

function appendSequentialText(
  msg: ChatMessage,
  kind: 'text' | 'thinking',
  chunk: string,
): MessageBlock[] | undefined {
  if (!chunk) return msg.blocks;
  const blocks = msg.blocks ? [...msg.blocks] : [];
  const last = blocks[blocks.length - 1];
  if (last && last.kind === kind) {
    blocks[blocks.length - 1] = {
      ...last,
      content: (last.content || '') + chunk,
    };
    return blocks;
  }
  if (!chunk.trim()) {
    return msg.blocks;
  }
  blocks.push({
    id: createBlockId(msg, blocks),
    kind,
    content: chunk,
  });
  return blocks;
}

/** 向助手消息追加流式正文增量，同时按时序维护交错时间线 `blocks`。 */
export function appendTextBlock(msg: ChatMessage, chunk: string): ChatMessage {
  return {
    ...msg,
    content: msg.content + chunk,
    blocks: appendSequentialText(msg, 'text', chunk),
  };
}

/** 向助手消息追加流式思考增量，同时按时序维护交错时间线 `blocks`。 */
export function appendThinkingBlock(msg: ChatMessage, chunk: string): ChatMessage {
  return {
    ...msg,
    reasoning: (msg.reasoning || '') + chunk,
    blocks: appendSequentialText(msg, 'thinking', chunk),
  };
}

function upsertToolInList(
  list: ToolExecution[],
  targetId: string,
  toolName: string,
  command: string,
): ToolExecution[] {
  const next = [...list];
  const idx = next.findIndex((t) => t.toolCallId === targetId);
  if (idx >= 0) {
    next[idx] = {
      ...next[idx],
      toolName: toolName || next[idx].toolName,
      command: command || next[idx].command,
    };
    return next;
  }
  next.push({
    toolCallId: targetId,
    toolName: toolName || 'tool',
    command,
    status: 'running',
  });
  return next;
}

/** 记录工具调用发起或参数补全；同一步连续调用合并入同一 `tool_group` 块。 */
export function upsertToolCallBlock(
  msg: ChatMessage,
  payload: { toolCallId?: string; toolName?: string; command?: string },
): ChatMessage {
  const targetId = payload.toolCallId || `tool_${Date.now()}`;
  const toolName = payload.toolName || 'tool';
  const command = payload.command || '';
  const nextTools = upsertToolInList(msg.tools || [], targetId, toolName, command);

  const blocks = msg.blocks ? [...msg.blocks] : [];
  for (let i = blocks.length - 1; i >= 0; i -= 1) {
    const block = blocks[i];
    if (block.kind === 'tool_group' && block.tools?.some((t) => t.toolCallId === targetId)) {
      blocks[i] = {
        ...block,
        tools: upsertToolInList(block.tools, targetId, toolName, command),
      };
      return { ...msg, tools: nextTools, blocks };
    }
  }

  const last = blocks[blocks.length - 1];
  if (last && last.kind === 'tool_group') {
    blocks[blocks.length - 1] = {
      ...last,
      tools: upsertToolInList(last.tools || [], targetId, toolName, command),
    };
  } else {
    blocks.push({
      id: createBlockId(msg, blocks),
      kind: 'tool_group',
      tools: [{ toolCallId: targetId, toolName, command, status: 'running' }],
    });
  }
  return { ...msg, tools: nextTools, blocks };
}

function applyToolResultInList(
  list: ToolExecution[],
  payload: {
    toolCallId?: string;
    result?: string;
    status?: ToolExecution['status'];
  },
): { updated: boolean; tools: ToolExecution[] } {
  const next = [...list];
  let targetIndex = payload.toolCallId
    ? next.findIndex((t) => t.toolCallId === payload.toolCallId)
    : -1;
  if (targetIndex === -1) {
    targetIndex = next.findLastIndex((t) => t.status === 'running');
  }
  if (targetIndex < 0) return { updated: false, tools: list };
  next[targetIndex] = {
    ...next[targetIndex],
    output: (next[targetIndex].output || '') + (payload.result || ''),
    status: payload.status || 'completed',
  };
  return { updated: true, tools: next };
}

/** 更新工具执行输出与终态，同步刷新时间线对应 `tool_group` 内的记录。 */
export function applyToolResultBlock(
  msg: ChatMessage,
  payload: {
    toolCallId?: string;
    result?: string;
    status?: ToolExecution['status'];
  },
): ChatMessage {
  const flatResult = applyToolResultInList(msg.tools || [], payload);
  const blocks = msg.blocks ? [...msg.blocks] : undefined;
  if (blocks) {
    for (let i = blocks.length - 1; i >= 0; i -= 1) {
      const block = blocks[i];
      if (block.kind !== 'tool_group' || !block.tools?.length) continue;
      if (payload.toolCallId) {
        const idx = block.tools.findIndex((t) => t.toolCallId === payload.toolCallId);
        if (idx >= 0) {
          const updated = applyToolResultInList(block.tools, payload);
          blocks[i] = { ...block, tools: updated.tools };
          break;
        }
      } else {
        const updated = applyToolResultInList(block.tools, payload);
        if (updated.updated) {
          blocks[i] = { ...block, tools: updated.tools };
          break;
        }
      }
    }
  }
  return { ...msg, tools: flatResult.tools, blocks };
}

/** 将思考过程文本拆分为可读句子；流式未结束时丢弃未完结半句。 */
export function splitReasoningSentences(text: string, includePartial: boolean): string[] {
  const clean = text.trim().replace(/\s*\n\s*/g, ' ');
  if (!clean) return [];
  const parts = clean.split(/(?<=[。！？!?；;])\s*/).filter(Boolean);
  if (!includePartial && parts.length > 0) {
    const last = parts[parts.length - 1];
    if (!/[。！？!?；;]$/.test(last)) {
      parts.pop();
    }
  }
  return parts;
}

export interface ToolGroupSummary {
  label: string;
  running: boolean;
  failedCount: number;
}

/** 生成步进工具组的单行中文折叠摘要（如“已网页搜索（6 次）”）。 */
export function formatToolGroupSummary(
  tools: ToolExecution[],
  isGenerating: boolean,
): ToolGroupSummary {
  const running = isGenerating && tools.some((t) => t.status === 'running');
  const failedCount = tools.filter((t) => t.status === 'failed').length;
  const actions: string[] = [];
  for (const tool of tools) {
    const label = TOOL_ACTION_LABELS[tool.toolName] || tool.toolName || '调用工具';
    if (!actions.includes(label)) actions.push(label);
  }

  const head = actions.slice(0, 2).join('、') + (actions.length > 2 ? '等' : '');
  let label: string;
  if (running) {
    label = tools.length > 1 ? `正在${head}（${tools.length} 项）` : `正在${head}…`;
  } else if (tools.length === 1) {
    label = `已${head}`;
  } else if (actions.length === 1) {
    label = `已${head}（${tools.length} 次）`;
  } else {
    label = `已${head}（${tools.length} 项）`;
  }

  if (!running && failedCount > 0) {
    label += ` · ${failedCount} 项失败`;
  }
  return { label, running, failedCount };
}

/** 将单个运行期 ToolExecution 转为通用 TraceNode，供 ToolCall 卡片复用。 */
export function toolExecutionToTraceNode(
  tool: ToolExecution,
  resolveToolLabel?: (toolName: string) => string | undefined,
): TraceNode {
  const running = tool.status === 'running';
  const failed = tool.status === 'failed';
  const status = running ? 'running' : failed ? 'failed' : 'completed';
  const isCommand =
    tool.toolName === 'execute_command' ||
    tool.toolName === 'execute' ||
    tool.toolName === 'terminal';

  if (isCommand) {
    return {
      id: tool.toolCallId,
      type: 'terminal',
      toolName: tool.toolName,
      primary: '运行',
      secondary: tool.command,
      command: tool.command,
      output: tool.output,
      exitCode: failed ? 1 : 0,
      status,
    };
  }

  const label = resolveToolLabel?.(tool.toolName);
  return {
    id: tool.toolCallId,
    type: 'tool',
    toolName: tool.toolName,
    primary: label || tool.toolName,
    secondary: tool.command || tool.toolName,
    command: tool.command,
    status,
    details: tool.output
      ? tool.output
          .split('\n')
          .filter(Boolean)
          .map((text) => ({ text, tone: (failed ? 'error' : 'muted') as 'error' | 'muted' }))
      : undefined,
  };
}

/** 格式化任务总耗时文案（如“用时 18 秒”或“用时 26分钟 18秒”）。 */
export function formatElapsedDuration(totalSeconds?: number): string {
  const safe = Math.max(0, Math.round(totalSeconds ?? 0));
  if (safe < 60) return `用时 ${safe} 秒`;
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const seconds = safe % 60;
  if (hours > 0) {
    return seconds > 0
      ? `用时 ${hours}小时 ${minutes}分钟 ${seconds}秒`
      : `用时 ${hours}小时 ${minutes}分钟`;
  }
  return seconds > 0 ? `用时 ${minutes}分钟 ${seconds}秒` : `用时 ${minutes}分钟`;
}

/** 拆分为“前置过程链（思考、中间说明、工具组）”与“最终结论正文”，供结束后统一折叠过程链。 */
export function splitProcessAndFinalBlocks(blocks: MessageBlock[]): {
  processBlocks: MessageBlock[];
  finalTextBlock?: MessageBlock;
} {
  if (blocks.length === 0) return { processBlocks: [] };
  const hasProcess = blocks.some((b) => b.kind !== 'text') || blocks.length > 1;
  if (!hasProcess) {
    return { processBlocks: [], finalTextBlock: blocks[0] };
  }
  const last = blocks[blocks.length - 1];
  if (last.kind === 'text' && last.content?.trim()) {
    return {
      processBlocks: blocks.slice(0, -1),
      finalTextBlock: last,
    };
  }
  return { processBlocks: blocks };
}
