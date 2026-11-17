import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Brain, ChevronDown } from 'lucide-react';
import styles from './NestedReasoningBlock.module.css';

const SENT_H = 46;
const GAP = 8;
const MAX_H = 184;
const EXPANDED_MAX_H = 240;
const FADE = 18;

export interface NestedReasoningBlockProps {
  sentences: string[];
  isActive: boolean;
  isFinished: boolean;
  durationSeconds?: number;
  /** 为 true 时即使正在思考也默认折叠，由用户点击手动展开。 */
  collapseWhileActive?: boolean;
}

/**
 * 嵌套推理块：流式展示思考句子，默认可折叠并通过点击手动展开。
 */
export const NestedReasoningBlock: React.FC<NestedReasoningBlockProps> = ({
  sentences,
  isActive,
  isFinished,
  durationSeconds,
  collapseWhileActive = false,
}) => {
  const [manualOpen, setManualOpen] = useState(false);
  const [fade, setFade] = useState({ top: false, bottom: false });
  const viewportRef = useRef<HTMLDivElement>(null);

  const canToggle = isFinished || collapseWhileActive;
  const expanded = collapseWhileActive
    ? manualOpen
    : (isFinished ? manualOpen : isActive);
  const count = sentences.length;
  const contentH = count > 0 ? count * SENT_H + (count - 1) * GAP : 0;
  const capped = contentH > MAX_H;
  const viewH = capped ? MAX_H : contentH;
  const scrollable = (isFinished || collapseWhileActive) && expanded;
  const translate = scrollable ? 0 : capped ? MAX_H - FADE - contentH : 0;

  const showTop = scrollable ? fade.top : capped;
  const showBottom = scrollable ? fade.bottom : capped;
  const useMask = scrollable ? (showTop || showBottom) : capped;

  const mask = useMask
    ? `linear-gradient(to bottom, transparent 0, #000 ${showTop ? FADE : 0}px, #000 calc(100% - ${
        showBottom ? FADE : 0
      }px), transparent 100%)`
    : 'none';

  const syncFade = useCallback(() => {
    const el = viewportRef.current;
    if (!el) return;
    setFade({
      top: el.scrollTop > 1,
      bottom: el.scrollTop + el.clientHeight < el.scrollHeight - 1,
    });
  }, []);

  useEffect(() => {
    if (scrollable) syncFade();
  }, [scrollable, sentences, syncFade]);

  return (
    <div className={styles.container}>
      <button
        type="button"
        disabled={!canToggle}
        aria-expanded={expanded}
        onClick={() => canToggle && setManualOpen((v) => !v)}
        className={`${styles.trigger} ${canToggle ? styles.triggerClickable : ''}`}
      >
        <span className={styles.iconWrap}>
          <Brain className={`${styles.brain} ${canToggle ? styles.brainHoverable : ''} ${manualOpen ? styles.brainHidden : ''}`} aria-hidden="true" />
          {canToggle && (
            <ChevronDown
              className={`${styles.chevron} ${manualOpen ? styles.chevronOpen : ''}`}
              aria-hidden="true"
            />
          )}
        </span>

        <span className={styles.label}>
          {isFinished ? (
            <span className={styles.finishedLabel}>
              {durationSeconds !== undefined ? (
                <>
                  思考 <span className={styles.duration}>{durationSeconds}s</span>
                </>
              ) : (
                '已思考'
              )}
            </span>
          ) : (
            <span className={styles.thinkingShimmer}>思考中…</span>
          )}
        </span>
      </button>

      <div className={`${styles.channel} ${expanded ? styles.channelOpen : ''}`}>
        <div className={styles.channelInner}>
          <div className={styles.timeline}>
            <div
              ref={viewportRef}
              className={`${styles.viewport} ${scrollable ? styles.scrollable : ''}`}
              style={{
                ...(scrollable ? { maxHeight: `${EXPANDED_MAX_H}px` } : { height: `${viewH}px` }),
                WebkitMaskImage: mask,
                maskImage: mask,
              }}
              onScroll={scrollable ? syncFade : undefined}
            >
              <div
                className={`${styles.sentenceList} ${scrollable ? styles.sentenceListExpanded : ''}`}
                style={scrollable ? undefined : { transform: `translateY(${translate}px)` }}
              >
                {sentences.slice(0, count).map((line, i) => (
                  <p key={i} className={`${styles.sentence} ${scrollable ? styles.sentenceExpanded : ''}`}>
                    {line}
                  </p>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
