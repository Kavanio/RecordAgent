import React, { useMemo, useState } from 'react';
import { AlertCircle, ChevronDown, Layers, Loader2 } from 'lucide-react';
import type { ToolExecution } from '../../types/chat';
import { formatToolGroupSummary, toolExecutionToTraceNode } from '../../features/chat/messageBlocks';
import { ToolCall, toolNodeToToolCallProps } from './ToolCall';
import styles from './ToolGroupBlock.module.css';

export interface ToolGroupBlockProps {
  tools: ToolExecution[];
  isGenerating: boolean;
  defaultOpen?: boolean;
  resolveToolLabel?: (toolName: string) => string | undefined;
}

/**
 * 步进式工具组折叠条：默认收起为单行操作摘要（如“已网页搜索（6 次）”），点击展开查看各工具详情。
 */
export const ToolGroupBlock: React.FC<ToolGroupBlockProps> = ({
  tools,
  isGenerating,
  defaultOpen = false,
  resolveToolLabel,
}) => {
  const [open, setOpen] = useState(defaultOpen);
  const summary = useMemo(
    () => formatToolGroupSummary(tools, isGenerating),
    [tools, isGenerating],
  );

  if (tools.length === 0) return null;

  return (
    <div className={styles.container}>
      <button
        type="button"
        className={styles.trigger}
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className={styles.iconWrap}>
          {summary.running ? (
            <Loader2 size={14} className={styles.spinner} aria-hidden="true" />
          ) : summary.failedCount > 0 ? (
            <AlertCircle size={14} className={styles.failedIcon} aria-hidden="true" />
          ) : (
            <Layers size={14} className={styles.doneIcon} aria-hidden="true" />
          )}
        </span>

        <span className={`${styles.label} ${summary.running ? styles.runningShimmer : ''}`}>
          {summary.label}
        </span>

        <ChevronDown
          size={13}
          aria-hidden="true"
          className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`}
        />
      </button>

      <div className={`${styles.channel} ${open ? styles.channelOpen : ''}`}>
        <div className={styles.channelInner}>
          <div className={styles.list}>
            {tools.map((tool, index) => (
              <ToolCall
                key={tool.toolCallId || index}
                {...toolNodeToToolCallProps(toolExecutionToTraceNode(tool, resolveToolLabel))}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
