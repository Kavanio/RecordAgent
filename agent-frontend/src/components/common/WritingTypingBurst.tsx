import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { Editor } from '@tiptap/core';
import { WritingBurstParticles } from './WritingBurstParticles';
import { useWritingEffectPreferences } from './useWritingEffectPreferences';
import { captureWritingCaret } from './writingCaret';
import styles from './WritingTypingBurst.module.css';

interface WritingTypingBurstProps {
  editor: Editor | null;
}

interface Burst {
  id: number;
  x: number;
  y: number;
}

const MAX_BURSTS = 6;
const LIFETIME_MS = 560;

/** 正文光标旁的微型打字烟花；仅观察输入，不修改编辑器文档或选区。 */
export function WritingTypingBurst({ editor }: WritingTypingBurstProps) {
  const [bursts, setBursts] = useState<Burst[]>([]);
  const nextId = useRef(0);
  const preferences = useWritingEffectPreferences();
  const preferencesRef = useRef(preferences);
  preferencesRef.current = preferences;

  useEffect(() => {
    if (!editor || editor.isDestroyed || !preferences.enabled) return;
    const dom = editor.view.dom;
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const timers = new Map<number, number>();
    let frame: number | null = null;
    let composing = false;
    let pendingCaret: { x: number; y: number } | null = null;

    const clear = () => {
      if (frame !== null) window.cancelAnimationFrame(frame);
      frame = null;
      pendingCaret = null;
      timers.forEach(timer => window.clearTimeout(timer));
      timers.clear();
      setBursts(current => current.length ? [] : current);
    };

    const queueBurst = (text: string | null) => {
      if (!text?.trim() || media.matches || !preferencesRef.current.enabled) return;
      pendingCaret = captureWritingCaret(editor);
      if (frame !== null) return;
      // 同帧合并为一次，但保留最后输入文字的末尾坐标，不读取之后的新选区。
      frame = window.requestAnimationFrame(() => {
        frame = null;
        const caret = pendingCaret;
        pendingCaret = null;
        if (!caret || !preferencesRef.current.enabled || media.matches || editor.isDestroyed || !editor.view.hasFocus() || composing) return;
        const { x, y } = caret;
        if (!Number.isFinite(x) || !Number.isFinite(y)
          || x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) return;
        const id = ++nextId.current;
        setBursts(current => [...current.slice(-(MAX_BURSTS - 1)), { id, x, y }]);
        if (timers.size >= MAX_BURSTS) {
          const oldestId = timers.keys().next().value;
          if (oldestId !== undefined) {
            window.clearTimeout(timers.get(oldestId));
            timers.delete(oldestId);
          }
        }
        // 动画结束即清理；后台页面未派发 animationend 时也不会堆积节点。
        timers.set(id, window.setTimeout(() => {
          timers.delete(id);
          setBursts(current => current.filter(burst => burst.id !== id));
        }, LIFETIME_MS));
      });
    };

    const onInput = (event: Event) => {
      const input = event as InputEvent;
      if (composing || input.isComposing
        || !['insertText', 'insertCompositionText', 'insertFromComposition', 'insertReplacementText'].includes(input.inputType)) return;
      if (event.target instanceof Element && event.target.closest('[contenteditable="false"]')) return;
      queueBurst(input.data);
    };
    const onCompositionStart = () => { composing = true; };
    const onCompositionEnd = (event: CompositionEvent) => {
      composing = false;
      queueBurst(event.data);
    };

    dom.addEventListener('input', onInput);
    dom.addEventListener('compositionstart', onCompositionStart);
    dom.addEventListener('compositionend', onCompositionEnd);
    dom.addEventListener('blur', clear, true);
    window.addEventListener('scroll', clear, true);
    window.addEventListener('resize', clear);
    media.addEventListener('change', clear);
    return () => {
      dom.removeEventListener('input', onInput);
      dom.removeEventListener('compositionstart', onCompositionStart);
      dom.removeEventListener('compositionend', onCompositionEnd);
      dom.removeEventListener('blur', clear, true);
      window.removeEventListener('scroll', clear, true);
      window.removeEventListener('resize', clear);
      media.removeEventListener('change', clear);
      clear();
    };
  }, [editor, preferences.enabled]);

  if (!preferences.enabled || !bursts.length) return null;
  return createPortal(
    <div className={styles.layer} aria-hidden="true">
      {bursts.map(burst => (
        <span key={burst.id} className={styles.burst} data-typing-burst=""
          style={{ left: burst.x, top: burst.y }}>
          <WritingBurstParticles preferences={preferences} seed={burst.id} />
        </span>
      ))}
    </div>,
    document.body,
  );
}
