import React from 'react';
import type { ChatMessage } from '../../types/chat';
import { Copy, ThumbsUp, ThumbsDown, Maximize2 } from 'lucide-react';
import { AgentResponse } from './AgentResponse';
import { SubagentActivity } from './SubagentActivity';
import { MarkdownContent } from '../common/MarkdownContent';
import { TokenUsageTrigger } from './TokenUsageTrigger';
import styles from './ChatWorkspace.module.css';

interface AssistantMessageItemProps {
  msg: ChatMessage;
  tokenUsageActive: boolean;
  onOpenTokenUsage: (messageId: string) => void;
}

export const AssistantMessageItem: React.FC<AssistantMessageItemProps> = ({
  msg,
  tokenUsageActive,
  onOpenTokenUsage,
}) => {
  const isGenerating = msg.status === undefined && msg.elapsedTime === undefined;
  const elapsedSec = msg.elapsedTime !== undefined
    ? msg.elapsedTime
    : (isGenerating && (msg.startTime || msg.createdAt)
      ? Math.max(1, Math.floor((Date.now() - (msg.startTime || msg.createdAt)) / 1000))
      : undefined);

  const handleCopy = () => {
    if (msg.content) {
      navigator.clipboard.writeText(msg.content);
    }
  };

  return (
    <div className={styles.assistantMessage}>
      {/* Agent 响应过程区：思考 + 工具/终端时间线 */}
      <AgentResponse
        msg={msg}
        isGenerating={isGenerating}
        elapsedSeconds={elapsedSec}
      />

      <SubagentActivity progress={msg.subagentProgress || []} />

      {/* 最终回答正文 */}
      {msg.content?.startsWith('## 验收报告') ? (
        <article className={styles.acceptanceReport}>
          <MarkdownContent content={msg.content} />
        </article>
      ) : (
        msg.content && <MarkdownContent content={msg.content} />
      )}

      {(msg.status === 'CANCELLED' || msg.status === 'FAILED') && (
        <div
          className={`${styles.terminalStatus} ${msg.status === 'FAILED' ? styles.failedStatus : ''}`}
          role={msg.status === 'FAILED' ? 'alert' : 'status'}
        >
          {msg.status === 'CANCELLED'
            ? '已停止生成'
            : (msg.failureReason || '生成失败，请重试。')}
        </div>
      )}

      {(msg.content || msg.usage) && (
        <div className={styles.messageFooter}>
          {/* 消息底部操作工具栏：复制、赞、踩、全屏/分享 */}
          {msg.content && (
            <div className={styles.messageActions}>
              <button className={styles.actionBtn} title="复制内容" aria-label="复制内容" onClick={handleCopy}>
                <Copy size={14} />
              </button>
              <button className={styles.actionBtn} title="即将推出" aria-label="赞（即将推出）" disabled>
                <ThumbsUp size={14} />
              </button>
              <button className={styles.actionBtn} title="即将推出" aria-label="踩（即将推出）" disabled>
                <ThumbsDown size={14} />
              </button>
              <button className={styles.actionBtn} title="即将推出" aria-label="全屏/扩展（即将推出）" disabled>
                <Maximize2 size={14} />
              </button>
            </div>
          )}
          <TokenUsageTrigger
            usage={msg.usage}
            active={tokenUsageActive}
            onOpen={() => onOpenTokenUsage(msg.id)}
          />
        </div>
      )}
    </div>
  );
};
