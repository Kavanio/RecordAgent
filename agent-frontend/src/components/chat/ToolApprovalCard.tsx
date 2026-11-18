import React, { useCallback, useEffect, useMemo, useState } from 'react';
import type { PermissionDecisionInput, PermissionToolPayload } from '../../services/api';
import { TOOL_NAME_MAP, formatParamValue, getRiskBadge } from './toolApprovalPresentation';
import styles from './ToolApprovalCard.module.css';

export interface ToolApprovalCardProps {
  /** 待审核的工具列表 */
  tools: PermissionToolPayload[];
  /** 是否正在提交请求 */
  isSubmitting: boolean;
  /** 提交审批决定 */
  onDecision: (decisions: PermissionDecisionInput[]) => void;
  /** 可选关闭/取消回调 */
  onCancel?: () => void;
  /** 扩展 class */
  className?: string;
}

type DecisionType = 'allow' | 'deny';

/**
 * 极简纯白优雅风格的工具审批卡片组件（Minimal White）
 */
export const ToolApprovalCard: React.FC<ToolApprovalCardProps> = ({
  tools,
  isSubmitting,
  onDecision,
  onCancel,
  className,
}) => {
  const [rememberChoice, setRememberForSession] = useState(false);
  const canRememberBatch = tools.every((tool) => tool.toolName !== 'capture_screen');
  const rememberForSession = canRememberBatch && rememberChoice;
  // 参数列表默认全部折叠，需主动点击才展开
  const [openParams, setOpenParams] = useState<Record<string, boolean>>({});

  // 多工具时逐项决定的状态
  const [decisions, setDecisions] = useState<Record<string, DecisionType>>(() =>
    Object.fromEntries(tools.map((t) => [t.toolCallId, 'allow']))
  );

  useEffect(() => {
    setRememberForSession(false);
    // 默认保持参数折叠
    setOpenParams({});
    setDecisions(Object.fromEntries(tools.map((t) => [t.toolCallId, 'allow'])));
  }, [tools]);

  const isSingleTool = tools.length === 1;
  const singleTool = tools[0];

  // 切换折叠参数
  const toggleParamOpen = (toolCallId: string) => {
    setOpenParams((prev) => ({
      ...prev,
      [toolCallId]: !prev[toolCallId],
    }));
  };

  // 提交允许
  const handleAllow = useCallback(() => {
    if (isSubmitting) return;
    if (isSingleTool) {
      onDecision([
        {
          toolCallId: singleTool.toolCallId,
          approved: true,
          rememberForSession,
        },
      ]);
    } else {
      // 多工具：提交当前所选项
      onDecision(
        tools.map((tool) => ({
          toolCallId: tool.toolCallId,
          approved: decisions[tool.toolCallId] === 'allow',
          rememberForSession,
        }))
      );
    }
  }, [isSubmitting, isSingleTool, singleTool, onDecision, rememberForSession, tools, decisions]);

  // 提交拒绝
  const handleDeny = useCallback(() => {
    if (isSubmitting) return;
    if (onCancel) {
      onCancel();
    }
    onDecision(
      tools.map((tool) => ({
        toolCallId: tool.toolCallId,
        approved: false,
        rememberForSession,
      }))
    );
  }, [isSubmitting, onCancel, onDecision, tools, rememberForSession]);

  // 全部允许
  const handleAllowAll = () => {
    if (isSubmitting) return;
    onDecision(
      tools.map((tool) => ({
        toolCallId: tool.toolCallId,
        approved: true,
        rememberForSession,
      }))
    );
  };

  // 全部拒绝
  const handleDenyAll = () => {
    if (isSubmitting) return;
    onDecision(
      tools.map((tool) => ({
        toolCallId: tool.toolCallId,
        approved: false,
        rememberForSession,
      }))
    );
  };

  // 键盘快捷键监听：Esc 拒绝，Enter 允许
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (isSubmitting) return;

      const target = event.target as HTMLElement | null;
      if (
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') &&
        target.getAttribute('type') !== 'checkbox'
      ) {
        return;
      }

      if (event.key === 'Escape') {
        event.preventDefault();
        handleDeny();
      } else if (event.key === 'Enter' && !event.shiftKey && !event.metaKey && !event.ctrlKey) {
        event.preventDefault();
        handleAllow();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isSubmitting, handleDeny, handleAllow]);

  // 标题文案
  const title = useMemo(() => {
    return isSingleTool ? '确认执行此操作？' : `确认执行这 ${tools.length} 项操作？`;
  }, [isSingleTool, tools.length]);

  // 描述文案
  const description = useMemo(() => {
    if (isSingleTool && singleTool) {
      if (singleTool.riskDescription) {
        if (singleTool.riskDescription.startsWith('将') || singleTool.riskDescription.startsWith('该')) {
          return `${singleTool.riskDescription}。执行前请确认工具与参数是否符合你的预期。`;
        }
        return `该工具${singleTool.riskDescription}。执行前请确认工具与参数是否符合你的预期。`;
      }
      const displayName = TOOL_NAME_MAP[singleTool.toolName] || singleTool.toolName;
      return `该工具会${displayName}。执行前请确认工具与参数是否符合你的预期。`;
    }
    return `当前批次包含 ${tools.length} 个工具操作。逐项核对或批量确认后统一提交，未允许的操作不会执行。`;
  }, [isSingleTool, singleTool, tools.length]);

  return (
    <section
      className={`${styles.card} ${className || ''}`}
      role="dialog"
      aria-modal="true"
      aria-labelledby="approvalTitle"
    >
      {/* 顶部标题与关闭按钮 */}
      <div className={styles.top}>
        <div className={styles.heading}>
          <div className={styles.eyebrow}>Approval required</div>
          <h1 className={styles.title} id="approvalTitle">
            {title}
          </h1>
          <p className={styles.desc}>{description}</p>
        </div>

        <button
          className={styles.close}
          type="button"
          aria-label="关闭"
          disabled={isSubmitting}
          onClick={handleDeny}
          title="关闭 / 拒绝"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
            <path d="M7 7l10 10M17 7 7 17" />
          </svg>
        </button>
      </div>

      {/* 工具列表 */}
      <div className={styles.toolList}>
        {tools.map((tool) => {
          const toolName = tool.toolName;
          const displayName = TOOL_NAME_MAP[toolName] || toolName;
          const riskBadge = getRiskBadge(toolName, tool.riskDescription);
          const inputEntries = Object.entries(tool.input || {});
          const isOpen = Boolean(openParams[tool.toolCallId]);
          const currentDecision = decisions[tool.toolCallId] || 'allow';

          return (
            <div className={styles.tool} key={tool.toolCallId}>
              {/* 工具头部：名称、ID、风险徽章 */}
              <div className={styles.toolTop}>
                <div className={styles.toolCopy}>
                  <div className={styles.toolLabel}>Tool</div>
                  <div className={styles.toolMain}>
                    <span className={styles.toolName}>{displayName}</span>
                    <span className={styles.toolId}>{toolName}</span>
                  </div>
                </div>

                <div className={styles.toolHeaderRight}>
                  <div className={styles.risk}>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
                      <path d="M12 4 20 19H4L12 4Z" />
                      <path d="M12 9v4M12 16.5h.01" />
                    </svg>
                    {riskBadge}
                  </div>

                  {!isSingleTool && (
                    <div className={styles.itemDecision} role="group" aria-label={`${displayName} 的决定`}>
                      <button
                        type="button"
                        disabled={isSubmitting}
                        className={`${styles.itemBtn} ${currentDecision === 'deny' ? styles.itemDenyActive : ''}`}
                        onClick={() =>
                          setDecisions((prev) => ({
                            ...prev,
                            [tool.toolCallId]: 'deny',
                          }))
                        }
                      >
                        拒绝
                      </button>
                      <button
                        type="button"
                        disabled={isSubmitting}
                        className={`${styles.itemBtn} ${currentDecision === 'allow' ? styles.itemAllowActive : ''}`}
                        onClick={() =>
                          setDecisions((prev) => ({
                            ...prev,
                            [tool.toolCallId]: 'allow',
                          }))
                        }
                      >
                        允许
                      </button>
                    </div>
                  )}
                </div>
              </div>

              {/* 参数列表折叠/展开 */}
              <div className={styles.params}>
                <button
                  className={styles.paramsToggle}
                  type="button"
                  aria-expanded={isOpen}
                  onClick={() => toggleParamOpen(tool.toolCallId)}
                >
                  <span className={styles.paramsToggleLeft}>
                    <svg
                      className={`${styles.chevron} ${isOpen ? styles.chevronOpen : ''}`}
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.8"
                    >
                      <path d="m7 9 5 5 5-5" />
                    </svg>
                    {isOpen ? '收起参数' : '查看完整参数'}
                  </span>

                  <span className={styles.count}>{inputEntries.length} 项</span>
                </button>

                {isOpen && (
                  <div className={styles.paramsBody}>
                    {inputEntries.length === 0 ? (
                      <div className={styles.paramEmpty}>无传入参数</div>
                    ) : (
                      <div className={styles.paramList}>
                        {inputEntries.map(([key, val]) => (
                          <div className={styles.param} key={key}>
                            <div className={styles.paramKey}>{key}</div>
                            <div className={styles.paramValue}>{formatParamValue(val)}</div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* 底部记忆与操作按钮 */}
      <div className={styles.bottom}>
        {canRememberBatch && <label className={styles.remember}>
          <input
            type="checkbox"
            checked={rememberForSession}
            disabled={isSubmitting}
            onChange={(e) => setRememberForSession(e.target.checked)}
          />
          <span className={styles.check}>
            <svg viewBox="0 0 24 24" fill="none" strokeWidth="2.2">
              <path d="m5 12 4 4 10-10" />
            </svg>
          </span>
          本会话记住相同操作
        </label>}

        <div className={styles.actions}>
          {isSingleTool ? (
            <>
              <button
                className={`${styles.btn} ${styles.btnSecondary}`}
                type="button"
                disabled={isSubmitting}
                onClick={handleDeny}
              >
                拒绝
              </button>
              <button
                className={`${styles.btn} ${styles.btnPrimary}`}
                type="button"
                disabled={isSubmitting}
                onClick={handleAllow}
              >
                {isSubmitting ? '正在提交…' : '允许执行'}
              </button>
            </>
          ) : (
            <>
              <button
                className={`${styles.btn} ${styles.btnSecondary}`}
                type="button"
                disabled={isSubmitting}
                onClick={handleDenyAll}
              >
                全部拒绝
              </button>
              <button
                className={`${styles.btn} ${styles.btnSecondary}`}
                type="button"
                disabled={isSubmitting}
                onClick={handleAllowAll}
              >
                全部允许
              </button>
              <button
                className={`${styles.btn} ${styles.btnPrimary}`}
                type="button"
                disabled={isSubmitting}
                onClick={handleAllow}
              >
                {isSubmitting ? '正在提交…' : `提交 ${tools.length} 项决定`}
              </button>
            </>
          )}
        </div>
      </div>

      {/* 底部快捷键提示 */}
      <div className={styles.shortcut}>
        <kbd>Esc</kbd> 拒绝　·　<kbd>Enter</kbd> 允许
      </div>
    </section>
  );
};
