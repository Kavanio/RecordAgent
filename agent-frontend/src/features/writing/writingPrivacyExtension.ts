import { Extension } from '@tiptap/core';
import { Plugin, PluginKey } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import type { Node } from '@tiptap/pm/model';

interface PrivacyState { enabled: boolean; revealed: number | null }
export const writingPrivacyKey = new PluginKey<PrivacyState>('writingPrivacy');

/** 将光标或 DOM 位置归到最内层文本块；不将整个列表/引用作为一个可见区域。 */
export function writingBlockPosition(doc: Node, position: number): number | null {
  const resolved = doc.resolve(position);
  for (let depth = resolved.depth; depth > 0; depth--) {
    if (resolved.node(depth).isTextblock) return resolved.before(depth);
  }
  return doc.nodeAt(position)?.isTextblock ? position : null;
}

/** 视觉属性由 ProseMirror Decoration 管理，不变更文档，也不进入 HTML/Markdown 序列化。 */
export const WritingPrivacyExtension = Extension.create({
  name: 'writingPrivacy',
  addProseMirrorPlugins() {
    return [new Plugin<PrivacyState>({
      key: writingPrivacyKey,
      state: {
        init: () => ({ enabled: false, revealed: null }),
        apply: (transaction, previous) => {
          const patch = transaction.getMeta(writingPrivacyKey) as Partial<PrivacyState> | undefined;
          return patch ? { ...previous, ...patch } : previous;
        },
      },
      props: {
        decorations(state) {
          const privacy = writingPrivacyKey.getState(state);
          if (!privacy?.enabled) return DecorationSet.empty;
          const decorations: Decoration[] = [];
          state.doc.descendants((node, position) => {
            if (!node.isTextblock) return;
            decorations.push(Decoration.node(position, position + node.nodeSize, {
              'data-writing-private': 'true',
              ...(position === privacy.revealed ? { 'data-writing-revealed': 'true' } : {}),
            }));
          });
          return DecorationSet.create(state.doc, decorations);
        },
      },
    })];
  },
});
