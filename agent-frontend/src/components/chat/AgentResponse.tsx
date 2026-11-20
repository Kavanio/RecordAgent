import React, { useMemo, useState } from 'react';
import { ChevronDown, Database, FileCode2, FileText, Search, Terminal } from 'lucide-react';
import type { ChatMessage, ToolDefinition, TraceNode, ToolExecution } from '../../types/chat';
import {
  formatElapsedDuration,
  groupTimelineBlocks,
  resolveToolActionLabel,
  splitProcessAndFinalBlocks,
  splitReasoningSentences,
  toolExecutionToTraceNode,
  type DisplayTimelineBlock,
} from '../../features/chat/messageBlocks';
import { MarkdownContent } from '../common/MarkdownContent';
import { NestedReasoningBlock } from './NestedReasoningBlock';
import { ThinkingState } from './ThinkingState';
import { ToolGroupBlock } from './ToolGroupBlock';
import thinkingStyles from './ThinkingState.module.css';
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
  if (toolDef) {
    return typeof toolDef.label === 'function' ? toolDef.label(undefined) : toolDef.label;
  }
  return resolveToolActionLabel(toolName);
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

function renderTimelineBlock(
  block: DisplayTimelineBlock,
  index: number,
  options: {
    isLast: boolean;
    isGenerating: boolean;
    thinkingDurationSeconds?: number;
    acceptanceReportClassName?: string;
  },
): React.ReactNode {
  if (block.kind === 'thinking') {
    const raw = block.content || '';
    if (!raw.trim()) return null;
    const isActive = options.isGenerating && options.isLast;
    const parsed = splitReasoningSentences(raw, !isActive);
    const sentences = parsed.length > 0 ? parsed : [raw.trim()];
    return (
      <NestedReasoningBlock
        key={block.id || index}
        sentences={sentences}
        isActive={isActive}
        isFinished={!isActive}
        durationSeconds={options.thinkingDurationSeconds}
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
        isGenerating={options.isGenerating}
        isAnalyzing={options.isGenerating && options.isLast && Boolean(block.analyzingAfterTools)}
        resolveToolLabel={resolveToolLabel}
      />
    );
  }

  const text = block.content || '';
  if (!text.trim()) return null;
  if (options.isLast && options.acceptanceReportClassName && text.startsWith('## 验收报告')) {
    return (
      <article key={block.id || index} className={options.acceptanceReportClassName}>
        <MarkdownContent content={text} />
      </article>
    );
  }
  return <MarkdownContent key={block.id || index} content={text} />;
}

/**
 * Agent 响应过程区：流式进行中按阶段正文聚合呈现工作时间线（两段阶段结论间的连续思考与工具合并为
 * 单行活动摘要）；输出完成后将前置过程统一折叠为“用时 X 秒 >”，仅保留最终总结展示在下方。
 */
export const AgentResponse: React.FC<AgentResponseProps> = ({
  msg,
  isGenerating,
  elapsedSeconds,
  acceptanceReportClassName,
}) => {
  const [processExpanded, setProcessExpanded] = useState(false);
  const blocks = msg.blocks;
  const hasBlocks = Boolean(blocks && blocks.length > 0);
  const trace = useMemo(
    () => (hasBlocks ? [] : toTraceNodes(msg.reasoning, msg.tools, elapsedSeconds, isGenerating)),
    [hasBlocks, msg.reasoning, msg.tools, elapsedSeconds, isGenerating]
  );

  if (hasBlocks && blocks) {
    if (isGenerating) {
      const displayBlocks = groupTimelineBlocks(blocks);
      const thinkingCount = displayBlocks.filter((b) => b.kind === 'thinking').length;
      const thinkingDuration = thinkingCount === 1 ? elapsedSeconds : undefined;
      return (
        <div className={styles.container}>
          {displayBlocks.map((block, index) =>
            renderTimelineBlock(block, index, {
              isLast: index === displayBlocks.length - 1,
              isGenerating: true,
              thinkingDurationSeconds: thinkingDuration,
              acceptanceReportClassName,
            }),
          )}
        </div>
      );
    }

    const { processBlocks, finalTextBlock } = splitProcessAndFinalBlocks(blocks);
    const displayProcessBlocks = groupTimelineBlocks(processBlocks);
    const thinkingCount = displayProcessBlocks.filter((b) => b.kind === 'thinking').length;
    const thinkingDuration = thinkingCount === 1 ? elapsedSeconds : undefined;
    return (
      <div className={styles.container}>
        {displayProcessBlocks.length > 0 && (
          <div className={thinkingStyles.container}>
            <button
              type="button"
              aria-expanded={processExpanded}
              onClick={() => setProcessExpanded((prev) => !prev)}
              className={thinkingStyles.masterTrigger}
            >
              <span className={thinkingStyles.masterLabel}>
                <span className={thinkingStyles.elapsedText}>
                  {formatElapsedDuration(elapsedSeconds)}
                </span>
              </span>
              <ChevronDown
                aria-hidden="true"
                className={`${thinkingStyles.chevron} ${processExpanded ? thinkingStyles.chevronOpen : ''}`}
              />
            </button>

            <div className={`${thinkingStyles.channel} ${processExpanded ? thinkingStyles.channelOpen : ''}`}>
              <div className={thinkingStyles.channelInner}>
                <div className={`${thinkingStyles.timeline} ${styles.processTimeline}`}>
                  {displayProcessBlocks.map((block, index) =>
                    renderTimelineBlock(block, index, {
                      isLast: false,
                      isGenerating: false,
                      thinkingDurationSeconds: thinkingDuration,
                    }),
                  )}
                </div>
              </div>
            </div>
          </div>
        )}

        {finalTextBlock &&
          renderTimelineBlock(finalTextBlock, blocks.length - 1, {
            isLast: true,
            isGenerating: false,
            acceptanceReportClassName,
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
