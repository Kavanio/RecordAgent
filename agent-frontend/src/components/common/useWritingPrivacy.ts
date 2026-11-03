import { useCallback, useEffect, useLayoutEffect, useState, type RefObject } from 'react';
import type { Editor } from '@tiptap/core';
import { privacyShortcutLabel, updateWritingPrivacyPreferences } from '../../services/writingPrivacyPreferences';
import { useWritingPrivacyPreferences } from './useWritingPrivacyPreferences';
import { useMessage } from './Message';
import { writingBlockPosition, writingPrivacyKey } from '../../features/writing/writingPrivacyExtension';
import styles from './useWritingPrivacy.module.css';

/** 正文使用编辑器装饰，标题/目录使用临时显示标记，同一时刻仅揭示一个块。 */
export function useWritingPrivacy(editor: Editor | null, available: boolean, rootRef: RefObject<HTMLElement | null>) {
  const preferences = useWritingPrivacyPreferences();
  const { showMessage } = useMessage();
  const [saving, setSaving] = useState(false);
  const enabled = available && preferences.enabled;
  const toggle = useCallback(async () => {
    if (saving) return;
    setSaving(true);
    try { await updateWritingPrivacyPreferences({ enabled: !preferences.enabled }); }
    catch (error) { showMessage('error', error instanceof Error ? error.message : '防偷窥设置保存失败'); }
    finally { setSaving(false); }
  }, [preferences.enabled, saving, showMessage]);

  useEffect(() => {
    if (!available || !preferences.shortcut) return;
    const keydown = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.isComposing || event.repeat || event.altKey
        || !(event.metaKey || event.ctrlKey) || !event.shiftKey
        || event.key.toLowerCase() !== preferences.shortcut) return;
      // 配置快捷键时让录入框自行消费事件。
      if (event.target instanceof HTMLElement && event.target.closest('[data-privacy-shortcut]')) return;
      event.preventDefault();
      event.stopPropagation();
      void toggle();
    };
    window.addEventListener('keydown', keydown, true);
    return () => window.removeEventListener('keydown', keydown, true);
  }, [available, preferences.shortcut, toggle]);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root || !editor) return;
    editor.view.dispatch(editor.state.tr.setMeta(writingPrivacyKey, { enabled, revealed: null }));
    if (!enabled) return;
    let revealed: HTMLElement | null = null;
    let idleTimer: number | undefined;
    let composing = false;
    const cancelIdle = () => {
      window.clearTimeout(idleTimer);
      idleTimer = undefined;
    };
    const blockFor = (target: EventTarget | Node | null): HTMLElement | null => {
      const element = target instanceof Element ? target : target instanceof Node ? target.parentElement : null;
      const block = element?.closest<HTMLElement>('[data-writing-private]');
      if (!block || !root.contains(block)) return null;
      return block;
    };
    const reveal = (block: HTMLElement | null, position: number | null = null) => {
      revealed?.removeAttribute('data-writing-revealed');
      revealed = block && !editor.view.dom.contains(block) ? block : null;
      revealed?.setAttribute('data-writing-revealed', 'true');
      if (block && !revealed) position = writingBlockPosition(editor.state.doc, editor.view.posAtDOM(block, 0));
      if (writingPrivacyKey.getState(editor.state)?.revealed !== position) {
        editor.view.dispatch(editor.state.tr.setMeta(writingPrivacyKey, { revealed: position }));
      }
    };
    const clear = () => { cancelIdle(); reveal(null); };
    const deactivate = () => { composing = false; clear(); };
    // 正文和标题共用输入静止期限；组合输入期间保持可见，确认后才开始计时。
    const scheduleHide = () => {
      cancelIdle();
      if (!composing) idleTimer = window.setTimeout(clear, 1500);
    };
    const caret = () => {
      if (editor.isFocused && document.hasFocus() && !document.hidden) {
        reveal(null, writingBlockPosition(editor.state.doc, editor.state.selection.from));
        scheduleHide();
      }
    };
    const hover = (event: Event) => {
      if (document.hasFocus() && !document.hidden) {
        cancelIdle();
        reveal(blockFor(event.target));
      }
    };
    const input = (event: Event) => {
      if (event.target instanceof Node && !editor.view.dom.contains(event.target)) {
        hover(event);
        if (revealed) scheduleHide();
      }
    };
    const compositionStart = () => { composing = true; cancelIdle(); };
    const compositionEnd = (event: Event) => { composing = false; caret(); input(event); };
    root.addEventListener('mouseover', hover);
    root.addEventListener('mousemove', hover);
    root.addEventListener('mouseleave', clear);
    root.addEventListener('focusin', hover);
    root.addEventListener('focusout', deactivate);
    root.addEventListener('input', input);
    root.addEventListener('compositionstart', compositionStart);
    root.addEventListener('compositionend', compositionEnd);
    root.addEventListener('scroll', clear, true);
    window.addEventListener('blur', deactivate);
    document.addEventListener('visibilitychange', deactivate);
    editor.on('update', caret);
    editor.on('selectionUpdate', caret);
    return () => {
      clear();
      root.removeEventListener('mouseover', hover);
      root.removeEventListener('mousemove', hover);
      root.removeEventListener('mouseleave', clear);
      root.removeEventListener('focusin', hover);
      root.removeEventListener('focusout', deactivate);
      root.removeEventListener('input', input);
      root.removeEventListener('compositionstart', compositionStart);
      root.removeEventListener('compositionend', compositionEnd);
      root.removeEventListener('scroll', clear, true);
      window.removeEventListener('blur', deactivate);
      document.removeEventListener('visibilitychange', deactivate);
      editor.off('update', caret);
      editor.off('selectionUpdate', caret);
    };
  }, [editor, available, enabled, rootRef]);

  return { enabled, saving, toggle, className: enabled ? styles.protected : '', shortcut: privacyShortcutLabel(preferences.shortcut) };
}
