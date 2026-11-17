import React, { useMemo } from 'react';
import { Database, FileCode2, FileText, Search, Terminal } from 'lucide-react';
import type { ChatMessage, ToolDefinition, TraceNode, ToolExecution } from '../../types/chat';
import { splitReasoningSentences, toolExecutionToTraceNode } from '../../features/chat/messageBlocks';
import { MarkdownContent } from '../common/MarkdownContent';
import { NestedReasoningBlock } from './NestedReasoningBlock';
import { ThinkingState } from './ThinkingState';
import { ToolGroupBlock } from './ToolGroupBlock';
import styles from './AgentResponse.module.css';

/** 默认工具展示注册表 */
export const DEFAULT_TOOL_REGISTRY: Record<string, ToolDefinition> = {
  read_file: {
    name: 'read_file',
    label: '读取',
    icon: FileText,
    monoChip: true,
  },
  edit_file: {
    name: 'edit_file',
    label: '编辑',
    icon: FileCode2,
    iconClassName: '#d97706',
    monoChip: true,
  },
  execute_command: {
    name: 'execute_command',
    label: '运行',
    icon: Terminal,
    iconClassName: '#7c3aed',
    monoChip: true,
  },
  search_web: {
    name: 'search_web',
    label: '搜索',
    icon: Search,
  },
  query_database: {
    name: 'query_database',
    label: 'SQL 查询',
    icon: Database,
    iconClassName: '#059669',
    monoChip: true,
  },
};

function resolveToolLabel(toolName: string): string | undefined {
  const toolDef = DEFAULT_TOOL_REGISTRY[toolName];
  return typeof toolDef?.label === 'function' ? toolDef.label(undefined) : toolDef?.label;
}

function toTraceNodes(
  reasoning: string | undefined,
  tools: ToolExecution[] | undefined,
  elapsedSeconds: number | undefined,
  isGenerating: boolean
): TraceNode[] {
  const nodes: TraceNode[] = [];

  if (reasoning && reasoning.trim()) {
    nodes.push({
      type: 'reasoning',
      sentences: splitReasoningSentences(reasoning, !isGenerating),
      durationSeconds: elapsedSeconds,
    });
  }

  (tools || []).forEach((tool) => {
    nodes.push(toolExecutionToTraceNode(tool, resolveToolLabel));
  });

  return nodes;
}

export interface AgentResponseProps {
  msg: ChatMessage;
  isGenerating: boolean;
  elapsedSeconds?: number;
  acceptanceReportClassName?: string;
}

/**
 * Agent 响应过程区：优先按 `msg.blocks` 渲染交错时间线（思考、过程说明、默认折叠的工具组、结论）；
 * 旧记录无 `blocks` 时回退到“思考 + 工具/终端”总折叠时间线。
 */
export const AgentResponse: React.FC<AgentResponseProps> = ({
  msg,
  isGenerating,
  elapsedSeconds,
  acceptanceReportClassName,
}) => {
  const blocks = msg.blocks;
  const hasBlocks = Boolean(blocks && blocks.length > 0);
  const trace = useMemo(
    () => (hasBlocks ? [] : toTraceNodes(msg.reasoning, msg.tools, elapsedSeconds, isGenerating)),
    [hasBlocks, msg.reasoning, msg.tools, elapsedSeconds, isGenerating]
  );

  if (hasBlocks && blocks) {
    const thinkingCount = blocks.filter((b) => b.kind === 'thinking').length;
    return (
      <div className={styles.container}>
        {blocks.map((block, index) => {
          const isLast = index === blocks.length - 1;
          if (block.kind === 'thinking') {
            const raw = block.content || '';
            if (!raw.trim()) return null;
            const isActive = isGenerating && isLast;
            const parsed = splitReasoningSentences(raw, !isActive);
            const sentences = parsed.length > 0 ? parsed : [raw.trim()];
            return (
              <NestedReasoningBlock
                key={block.id || index}
                sentences={sentences}
                isActive={isActive}
                isFinished={!isActive}
                durationSeconds={thinkingCount === 1 ? elapsedSeconds : undefined}
                collapseWhileActive
              />
            );
          }

          if (block.kind === 'tool_group') {
            if (!block.tools || block.tools.length === 0) return null;
            return (
              <ToolGroupBlock
                key={block.id || index}
                tools={block.tools}
                isGenerating={isGenerating}
                resolveToolLabel={resolveToolLabel}
              />
            );
          }

          const text = block.content || '';
          if (!text.trim()) return null;
          if (isLast && acceptanceReportClassName && text.startsWith('## 验收报告')) {
            return (
              <article key={block.id || index} className={acceptanceReportClassName}>
                <MarkdownContent content={text} />
              </article>
            );
          }
          return <MarkdownContent key={block.id || index} content={text} />;
        })}
      </div>
    );
  }

  if (!isGenerating && trace.length === 0) return null;

  return (
    <div className={styles.container}>
      <ThinkingState
        nodes={trace}
        tools={DEFAULT_TOOL_REGISTRY}
        isWorking={isGenerating}
        elapsedSeconds={elapsedSeconds}
        workingLabel="正在处理"
        defaultExpanded={false}
      />
    </div>
  );
};
