import React, { useState } from 'react';
import { ChevronDown } from 'lucide-react';
import type { TraceNode, ToolDefinition } from '../../types/chat';
import { formatElapsedDuration } from '../../features/chat/messageBlocks';
import { NestedReasoningBlock } from './NestedReasoningBlock';
import { TracePillRow } from './TracePillRow';
import { ToolCall, toolNodeToToolCallProps } from './ToolCall';
import styles from './ThinkingState.module.css';

const PIXEL_DELAYS = Array.from({ length: 9 }, (_, i) => {
  const r = Math.floor(i / 3);
  const c = i % 3;
  return (c + Math.abs(r - 1)) * 90;
});

/** 3×3 像素点加载器 */
export function PixelDotsLoader() {
  return (
    <span aria-hidden="true" className={styles.pixelDots}>
      {PIXEL_DELAYS.map((delay, index) => (
        <span
          key={index}
          className={styles.pixelDot}
          style={{ animationDelay: `${delay}ms` }}
        />
      ))}
    </span>
  );
}

export interface ThinkingStateProps {
  nodes?: TraceNode[];
  tools?: Record<string, ToolDefinition>;
  isWorking: boolean;
  elapsedSeconds?: number;
  workingLabel?: string;
  defaultExpanded?: boolean;
}

/**
 * 思考状态总控：实时事件驱动的“工作中 → 共耗时 N 秒”折叠时间线。
 */
export const ThinkingState: React.FC<ThinkingStateProps> = ({
  nodes = [],
  tools = {},
  isWorking,
  elapsedSeconds = 0,
  workingLabel = '正在处理',
  defaultExpanded,
}) => {
  const [manualExpanded, setManualExpanded] = useState<boolean | null>(
    defaultExpanded !== undefined ? defaultExpanded : null
  );

  const activeIndex = isWorking ? nodes.length - 1 : nodes.length;
  const isGlobalExpanded = manualExpanded !== null ? manualExpanded : (defaultExpanded ?? false);

  if (nodes.length === 0 && !isWorking) return null;

  return (
    <div className={styles.container}>
      <button
        type="button"
        aria-expanded={isGlobalExpanded}
        onClick={() => setManualExpanded((prev) => !(prev !== null ? prev : (defaultExpanded ?? false)))}
        className={styles.masterTrigger}
      >
        {isWorking && <PixelDotsLoader />}

        <span className={styles.masterLabel}>
          {isWorking ? (
            <span className={styles.workingShimmer}>{workingLabel}</span>
          ) : (
            <span className={styles.elapsedText}>{formatElapsedDuration(elapsedSeconds)}</span>
          )}
        </span>

        <ChevronDown
          aria-hidden="true"
          className={`${styles.chevron} ${isGlobalExpanded ? styles.chevronOpen : ''}`}
        />
      </button>

      <div className={`${styles.channel} ${isGlobalExpanded ? styles.channelOpen : ''}`}>
        <div className={styles.channelInner}>
          <div className={styles.timeline}>
            {nodes.map((node, idx) => {
              const nodeRunning = node.status === 'running' || node.status === 'pending';
              const isNodeActive =
                node.type === 'reasoning'
                  ? isWorking && idx === activeIndex
                  : isWorking && nodeRunning;
              const isNodeFinished =
                node.type === 'reasoning'
                  ? !isWorking || idx < activeIndex
                  : !isWorking || !nodeRunning;

              if (node.type === 'reasoning' && node.sentences) {
                return (
                  <NestedReasoningBlock
                    key={node.id || idx}
                    sentences={node.sentences}
                    isActive={isNodeActive}
                    isFinished={isNodeFinished}
                    durationSeconds={node.durationSeconds}
                  />
                );
              }

              if (node.type === 'tool' || node.type === 'terminal' || node.type === 'search') {
                return (
                  <ToolCall
                    key={node.id || idx}
                    {...toolNodeToToolCallProps(node)}
                  />
                );
              }

              return (
                <TracePillRow
                  key={node.id || idx}
                  node={node}
                  isActive={isNodeActive}
                  isFinished={isNodeFinished}
                  toolRegistry={tools}
                />
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
};
