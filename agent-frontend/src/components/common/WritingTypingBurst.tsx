import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { Editor } from '@tiptap/core';
import { Circle, Heart, Sparkle, Star } from 'lucide-react';
import styles from './WritingTypingBurst.module.css';

interface WritingTypingBurstProps {
  editor: Editor | null;
}

interface Burst {
  id: number;
  x: number;
  y: number;
}

const PARTICLES = [Sparkle, Circle, Star, Circle, Heart, Sparkle, Circle, Star];
const MAX_BURSTS = 6;
const LIFETIME_MS = 560;

/** 正文光标旁的微型打字烟花；仅观察输入，不修改编辑器文档或选区。 */
export function WritingTypingBurst({ editor }: WritingTypingBurstProps) {
  const [bursts, setBursts] = useState<Burst[]>([]);
  const nextId = useRef(0);

  useEffect(() => {
    if (!editor || editor.isDestroyed) return;
    const dom = editor.view.dom;
    const media = window.matchMedia('(prefers-reduced-motion: reduce)');
    const timers = new Map<number, number>();
    let frame: number | null = null;
    let composing = false;

    const clear = () => {
      if (frame !== null) window.cancelAnimationFrame(frame);
      frame = null;
      timers.forEach(timer => window.clearTimeout(timer));
      timers.clear();
      setBursts(current => current.length ? [] : current);
    };

    const queueBurst = (text: string | null) => {
      if (!text?.trim() || media.matches || frame !== null) return;
      // 等待浏览器和 ProseMirror 更新选区；同帧的 IME 收尾事件合并为一次。
      frame = window.requestAnimationFrame(() => {
        frame = null;
        if (media.matches || editor.isDestroyed || !editor.view.hasFocus() || composing) return;
        let caret;
        try {
          caret = editor.view.coordsAtPos(editor.state.selection.head);
        } catch {
          // 视图销毁或光标暂时无布局时跳过装饰，不影响正文输入。
          return;
        }
        const x = caret.left + 2;
        const y = (caret.top + caret.bottom) / 2;
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
  }, [editor]);

  if (!bursts.length) return null;
  return createPortal(
    <div className={styles.layer} aria-hidden="true">
      {bursts.map(burst => (
        <span key={burst.id} className={styles.burst} data-typing-burst=""
          style={{ left: burst.x, top: burst.y }}>
          {PARTICLES.map((Icon, index) => (
            <span key={index} className={styles.particle}><Icon strokeWidth={1.8} /></span>
          ))}
        </span>
      ))}
    </div>,
    document.body,
  );
}
