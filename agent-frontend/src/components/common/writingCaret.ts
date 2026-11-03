import type { Editor } from '@tiptap/core';

/** 在输入事件发生时冻结刚输入文字末尾的视口坐标，避免后续选区移动改变爆点。 */
export function captureWritingCaret(editor: Editor): { x: number; y: number; clearance: number } | null {
  if (editor.isDestroyed) return null;
  const { dom } = editor.view;
  const selection = dom.ownerDocument.getSelection();
  const node = selection?.focusNode;
  const offset = selection?.focusOffset ?? 0;
  if (node && dom.contains(node) && node.nodeType === dom.ownerDocument.TEXT_NODE && offset > 0) {
    const range = dom.ownerDocument.createRange();
    // 量最后一个字的边缘；软换行时仍使用上一行字尾，而不是下一行的起点。
    const text = node.textContent ?? '';
    const start = offset > 1 && /[\uDC00-\uDFFF]/.test(text[offset - 1]) ? offset - 2 : offset - 1;
    range.setStart(node, start);
    range.setEnd(node, offset);
    const rects = typeof range.getClientRects === 'function' ? Array.from(range.getClientRects()) : [];
    const rect = rects.filter(item => item.height > 0).at(-1);
    if (rect) {
      const rtl = getComputedStyle(node.parentElement ?? dom).direction === 'rtl';
      return { x: rtl ? rect.left : rect.right, y: (rect.top + rect.bottom) / 2, clearance: rect.height / 2 + 12 };
    }
  }
  try {
    const caret = editor.view.coordsAtPos(editor.state.selection.head, -1);
    return { x: caret.left, y: (caret.top + caret.bottom) / 2, clearance: (caret.bottom - caret.top) / 2 + 12 };
  } catch {
    // 装饰不具备可靠坐标时直接跳过，不让暂时缺少布局影响输入。
    return null;
  }
}
