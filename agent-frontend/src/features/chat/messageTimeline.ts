import type { MessageBlock, ToolExecution } from '../../types/chat';

const TOOL_ACTION_LABELS: Record<string, string> = {
  read_file: '读取文件',
  readFile: '读取文件',
  write_file: '写入文件',
  writeFile: '写入文件',
  edit_file: '编辑文件',
  editFile: '编辑文件',
  list_dir: '查看目录',
  list_files: '查看目录',
  search_files: '搜索文件',
  grep_search: '搜索内容',
  grepFiles: '搜索内容',
  glob: '查找文件',
  globFiles: '查找文件',
  execute_command: '运行命令',
  execute: '运行命令',
  terminal: '运行命令',
  custom_bash: '运行命令',
  web_search: '网页搜索',
  search_web: '网页搜索',
  web_fetch: '读取网页',
  fetch_url: '读取网页',
  query_database: 'SQL 查询',
  method_search: '检索方法卡',
  reset_equipped_tools: '装载工具能力',
  acceptance_report: '生成验收报告',
  plan_enter: '进入计划模式',
  plan_write: '更新计划',
  plan_exit: '提交计划',
  session_recent: '查看近期会话',
  session_list: '列举会话',
  session_history: '读取历史对话',
  session_search: '搜索历史对话',
};

const TOOL_PREFIX_LABELS: Array<[string, string]> = [
  ['calendar_', '操作日历'],
  ['study_', '记录学习'],
  ['finance_', '查询财务'],
  ['record_', '操作资料'],
  ['desktop_', '桌面操作'],
];

/** 将底层工具名映射为面向用户的中文动作短语。 */
export function resolveToolActionLabel(toolName: string): string {
  if (!toolName) return '调用工具';
  if (TOOL_ACTION_LABELS[toolName]) return TOOL_ACTION_LABELS[toolName];
  for (const [prefix, label] of TOOL_PREFIX_LABELS) {
    if (toolName.startsWith(prefix)) return label;
  }
  return toolName;
}

export interface ToolGroupSummary {
  label: string;
  running: boolean;
  failedCount: number;
}

export interface DisplayTimelineBlock extends MessageBlock {
  /** 同阶段工具执行完毕后紧跟着仍有思考增量时为 true，流式态用于提示“正在分析结果…”。 */
  analyzingAfterTools?: boolean;
}

/** 生成阶段工具组的单行中文折叠摘要（如“已读取文件、编辑文件、运行命令（5 项）”）。 */
export function formatToolGroupSummary(
  tools: ToolExecution[],
  isGenerating: boolean,
  options?: { analyzingAfterTools?: boolean },
): ToolGroupSummary {
  const runningTools = isGenerating && tools.some((t) => t.status === 'running');
  const analyzing = isGenerating && !runningTools && Boolean(options?.analyzingAfterTools);
  const failedCount = tools.filter((t) => t.status === 'failed').length;
  const actions: string[] = [];
  for (const tool of tools) {
    const label = resolveToolActionLabel(tool.toolName);
    if (!actions.includes(label)) actions.push(label);
  }

  const head = actions.slice(0, 3).join('、') + (actions.length > 3 ? '等' : '');
  let label: string;
  if (runningTools) {
    label = tools.length > 1 ? `正在${head}（${tools.length} 项）` : `正在${head}…`;
  } else if (tools.length === 1) {
    label = `已${head}`;
  } else if (actions.length === 1) {
    label = `已${head}（${tools.length} 次）`;
  } else {
    label = `已${head}（${tools.length} 项）`;
  }

  if (!runningTools && failedCount > 0) {
    label += ` · ${failedCount} 项失败`;
  }
  if (analyzing) {
    label += ' · 正在分析结果…';
  }
  return { label, running: runningTools || analyzing, failedCount };
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

/** 拆分为“前置过程链（思考、阶段进展、工具组）”与“最终结论正文”，供结束后统一折叠过程链。 */
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

/**
 * 按阶段进展正文分段聚合展示时间线：
 * 两段正文之间连续的多轮思考与工具调用合并为单个阶段活动条，避免界面堆满碎片化的“已思考”与零散工具行；
 * 仅存在思考而尚未出现工具的片段仍保留独立思考块，不修改原始 blocks 持久化数据。
 */
export function groupTimelineBlocks(blocks: MessageBlock[]): DisplayTimelineBlock[] {
  const result: DisplayTimelineBlock[] = [];
  let pendingId = '';
  let pendingTools: ToolExecution[] = [];
  let pendingThinking: string[] = [];
  let lastNonTextKind: 'thinking' | 'tool_group' | undefined;

  const flushPending = () => {
    if (pendingTools.length > 0) {
      result.push({
        id: pendingId,
        kind: 'tool_group',
        tools: pendingTools,
        analyzingAfterTools: lastNonTextKind === 'thinking',
      });
    } else if (pendingThinking.length > 0) {
      result.push({
        id: pendingId,
        kind: 'thinking',
        content: pendingThinking.join('\n'),
      });
    }
    pendingId = '';
    pendingTools = [];
    pendingThinking = [];
    lastNonTextKind = undefined;
  };

  for (const block of blocks) {
    if (block.kind === 'text') {
      if (!block.content?.trim()) continue;
      flushPending();
      result.push({ ...block });
      continue;
    }
    if (block.kind === 'tool_group') {
      if (!block.tools?.length) continue;
      if (!pendingId) pendingId = block.id;
      pendingTools = [...pendingTools, ...block.tools];
      lastNonTextKind = 'tool_group';
      continue;
    }
    if (block.kind === 'thinking') {
      const text = block.content?.trim();
      if (!text) continue;
      if (!pendingId) pendingId = block.id;
      pendingThinking.push(text);
      lastNonTextKind = 'thinking';
    }
  }
  flushPending();
  return result;
}
