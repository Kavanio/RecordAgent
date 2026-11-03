import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { CheckIcon, EyeIcon, EyeSlashIcon, NotePencilIcon } from '@phosphor-icons/react';
import { EditorContent, ReactNodeViewRenderer, useEditor } from '@tiptap/react';
import type { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { CodeBlockLowlight } from '@tiptap/extension-code-block-lowlight';
import Placeholder from '@tiptap/extension-placeholder';
import TaskList from '@tiptap/extension-task-list';
import TaskItem from '@tiptap/extension-task-item';
import { Markdown } from '@tiptap/markdown';
import { all, createLowlight } from 'lowlight';
import { TiptapCodeBlockView } from '../ui/TiptapCodeBlockView';
import { createSmartLowlight } from '../ui/codeBlockLanguages';
import { TopBar } from './TopBar';
import { TopBarAction } from './TopBarAction';
import { useMessage } from './Message';
import { WritingTypingBurst } from './WritingTypingBurst';
import { useWritingPrivacy } from './useWritingPrivacy';
import { WritingPrivacyExtension } from '../../features/writing/writingPrivacyExtension';
import styles from './WritingEditorPage.module.css';

const smartLowlight = createSmartLowlight(createLowlight(all));

const CustomCodeBlock = CodeBlockLowlight.extend({
  addNodeView() {
    return ReactNodeViewRenderer(TiptapCodeBlockView);
  },
  addKeyboardShortcuts() {
    return {
      ...this.parent?.(),
      Tab: ({ editor }) => {
        const { state } = editor;
        const { selection } = state;
        const { $from, empty } = selection;
        if ($from.parent.type.name !== this.name) {
          return false;
        }
        const indent = '  ';
        if (empty) {
          return editor.commands.insertContent(indent);
        }
        return editor.commands.command(({ tr }) => {
          const { from, to } = selection;
          const text = state.doc.textBetween(from, to, '\n', '\n');
          const lines = text.split('\n');
          const indentedText = lines.map((line) => indent + line).join('\n');
          tr.replaceWith(from, to, state.schema.text(indentedText));
          return true;
        });
      },
      'Shift-Tab': ({ editor }) => {
        const { state } = editor;
        const { selection } = state;
        const { $from, empty } = selection;
        if ($from.parent.type.name !== this.name) {
          return false;
        }
        const tabSize = 2;
        if (empty) {
          return editor.commands.command(({ tr }) => {
            const { pos } = $from;
            const codeBlockStart = $from.start();
            const codeBlockEnd = $from.end();
            const allText = state.doc.textBetween(codeBlockStart, codeBlockEnd, '\n', '\n');
            const lines = allText.split('\n');

            let currentLineIndex = 0;
            let charCount = 0;
            const relativeCursorPos = pos - codeBlockStart;

            for (let i = 0; i < lines.length; i += 1) {
              if (charCount + lines[i].length >= relativeCursorPos) {
                currentLineIndex = i;
                break;
              }
              charCount += lines[i].length + 1;
            }

            const currentLine = lines[currentLineIndex];
            const leadingSpaces = currentLine.match(/^ */)?.[0] || '';
            const spacesToRemove = Math.min(leadingSpaces.length, tabSize);
            if (spacesToRemove === 0) return true;

            let lineStartPos = codeBlockStart;
            for (let i = 0; i < currentLineIndex; i += 1) {
              lineStartPos += lines[i].length + 1;
            }

            tr.delete(lineStartPos, lineStartPos + spacesToRemove);
            return true;
          });
        }

        return editor.commands.command(({ tr }) => {
          const { from, to } = selection;
          const text = state.doc.textBetween(from, to, '\n', '\n');
          const lines = text.split('\n');
          const reverseIndentText = lines
            .map((line) => {
              const leadingSpaces = line.match(/^ */)?.[0] || '';
              const spacesToRemove = Math.min(leadingSpaces.length, tabSize);
              return line.slice(spacesToRemove);
            })
            .join('\n');
          tr.replaceWith(from, to, state.schema.text(reverseIndentText));
          return true;
        });
      },
    };
  },
}).configure({
  lowlight: smartLowlight,
  enableTabIndentation: true,
  tabSize: 2,
  defaultLanguage: null,
});

interface WritingEditorPageProps {
  backLabel: string;
  identity: string;
  detail: string;
  initialTitle?: string;
  initialBody?: string;
  bodyPlaceholder?: string;
  meta?: ReactNode;
  footer?: ReactNode;
  saving?: boolean;
  privacyAvailable?: boolean;
  onBack: () => void;
  onSave: (value: { title?: string; body: string; html?: string }) => void | Promise<void>;
}

interface EditorHeading {
  id: string;
  level: number;
  label: string;
  pos: number;
}

function extractEditorHeadings(editor: Editor | null): EditorHeading[] {
  if (!editor) return [];
  const items: EditorHeading[] = [];
  editor.state.doc.descendants((node, pos) => {
    if (node.type.name === 'heading') {
      const label = node.textContent.trim();
      if (label) {
        items.push({
          id: `${pos}-${label}`,
          level: (node.attrs.level as number) || 1,
          label,
          pos,
        });
      }
    }
  });
  return items;
}

/** 项目唯一的沉浸式长文编辑器，日历手记与资料记录均复用此界面。支持输入 ## 空格即时渲染为二级标题。 */
export function WritingEditorPage({
  backLabel,
  identity,
  detail,
  initialTitle = '',
  initialBody = '',
  bodyPlaceholder = '开始写下今天……',
  meta,
  footer,
  saving = false,
  privacyAvailable = false,
  onBack,
  onSave,
}: WritingEditorPageProps) {
  const { showMessage } = useMessage();
  const [title, setTitle] = useState(initialTitle);
  const [headings, setHeadings] = useState<EditorHeading[]>([]);
  const [characterCount, setCharacterCount] = useState(0);
  const scrollAreaRef = useRef<HTMLDivElement>(null);
  const workspaceRef = useRef<HTMLElement>(null);

  const editor: Editor | null = useEditor({
    immediatelyRender: false,
    extensions: [
      StarterKit.configure({
        heading: {
          levels: [1, 2, 3, 4],
        },
        codeBlock: false,
      }),
      CustomCodeBlock,
      WritingPrivacyExtension,
      Markdown,
      TaskList,
      TaskItem.configure({
        nested: true,
      }),
      Placeholder.configure({
        placeholder: ({ node, pos, editor: ed }) => {
          if (node.type.name === 'heading') {
            return `标题 ${node.attrs.level}`;
          }
          if (pos !== undefined && ed) {
            try {
              const $pos = ed.state.doc.resolve(pos);
              if ($pos.parent.type.name === 'blockquote') {
                return '输入引用内容……';
              }
            } catch {
              // ignore
            }
          }
          return bodyPlaceholder || '输入正文，或按 ## 创建二级标题……';
        },
      }),
    ],
    content: initialBody,
    contentType: 'markdown',
    editorProps: {
      attributes: {
        class: styles.bodyEditor,
        role: 'textbox',
        'aria-label': '正文',
        'aria-multiline': 'true',
      },
      handlePaste: (_view, event) => {
        const clipboard = event.clipboardData;
        // 富文本与 IDE 复制保留原有处理；代码块中的粘贴始终保持原文。
        if (!editor || !clipboard || editor.isActive('codeBlock')
          || clipboard.getData('text/html') || clipboard.getData('vscode-editor-data')) return false;
        const text = clipboard.getData('text/plain');
        const hasMarkdown = /^(?:#{1,4}\s|>\s|[-+*]\s|\d+\.\s|`{3}|~{3})|(\*\*|__|~~|`|\*|_)(?=\S)[^\n]+?\1|(?<!!)\[[^\]\n]+\]\([^)\n]+\)/m.test(text);
        if (!hasMarkdown) return false;
        return editor.commands.insertContent(text, { contentType: 'markdown' });
      },
    },
    onCreate: ({ editor: ed }) => {
      setHeadings(extractEditorHeadings(ed));
      setCharacterCount(ed.getText().length);
    },
    onUpdate: ({ editor: ed }) => {
      setHeadings(extractEditorHeadings(ed));
      setCharacterCount(ed.getText().length);
    },
  });

  const privacy = useWritingPrivacy(editor, privacyAvailable, workspaceRef);
  const canSave = Boolean(title.trim() || (editor ? !editor.isEmpty : initialBody.trim())) && !saving;

  const save = useCallback(async () => {
    if (!canSave) return;
    try {
      const markdown = editor ? editor.getMarkdown() : initialBody;
      const html = editor ? editor.getHTML() : '';
      await onSave({ title: title.trim() || undefined, body: markdown.trim(), html });
    } catch (reason) {
      showMessage('error', reason instanceof Error ? reason.message : '保存失败，请重试');
    }
  }, [canSave, editor, initialBody, onSave, showMessage, title]);

  useEffect(() => {
    const handleSaveShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void save();
      }
    };
    window.addEventListener('keydown', handleSaveShortcut);
    return () => window.removeEventListener('keydown', handleSaveShortcut);
  }, [save]);

  const locateHeading = (heading: EditorHeading) => {
    if (!editor) return;
    editor.commands.focus(heading.pos);
    const domNode = editor.view.nodeDOM(heading.pos);
    if (domNode instanceof HTMLElement) {
      domNode.scrollIntoView({ behavior: 'smooth', block: 'center' });
    } else {
      const resolved = editor.view.domAtPos(heading.pos);
      const target = resolved.node instanceof HTMLElement ? resolved.node : resolved.node.parentElement;
      target?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  };

  const handleContainerClick = (e: React.MouseEvent<HTMLElement>) => {
    if (
      editor &&
      !editor.isFocused &&
      (e.target === e.currentTarget ||
        (e.target as HTMLElement).classList.contains(styles.editorLayout) ||
        (e.target as HTMLElement).classList.contains(styles.editor) ||
        (e.target as HTMLElement).classList.contains(styles.editorWrapper))
    ) {
      editor.commands.focus('end');
    }
  };

  return (
    <main ref={workspaceRef} className={`${styles.workspace} ${privacy.className}`}>
      <WritingTypingBurst editor={editor} />
      <TopBar
        onBack={onBack}
        backLabel={backLabel}
        icon={<NotePencilIcon size={16} weight="duotone" />}
        title={identity}
        subtitle={detail}
        actions={
          <>
            {privacyAvailable && <TopBarAction variant="outline" aria-pressed={privacy.enabled} disabled={privacy.saving}
              title={`逐块模糊，悬浮显示当前块；${privacy.shortcut}`} onClick={() => void privacy.toggle()}
              icon={privacy.enabled ? <EyeIcon size={15} /> : <EyeSlashIcon size={15} />}>
              {privacy.enabled ? '恢复可见' : '开启防偷窥'}
            </TopBarAction>}
            <TopBarAction
              variant="primary"
              disabled={!canSave}
              onClick={() => void save()}
              icon={<CheckIcon size={15} weight="bold" />}
            >
              {saving ? '保存中…' : '保存'}
            </TopBarAction>
          </>
        }
      />
      <div ref={scrollAreaRef} className={styles.scrollArea} onClick={handleContainerClick}>
        <div className={styles.editorLayout}>
          <article className={styles.editor}>
            <p className={styles.date}>{detail}</p>
            <input
              className={styles.titleInput}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="标题"
              aria-label="标题"
              data-writing-private="true"
              autoFocus
            />
            <div className={styles.metaRow}>
              {meta}
              <span>{characterCount} 字</span>
            </div>
            <div className={styles.editorWrapper}>
              <EditorContent editor={editor} />
            </div>
            {privacy.enabled && <p className={styles.saveHint} role="status">防偷窥已开启 · 悬浮查看当前块，停止输入 1.5 秒自动隐藏 · {privacy.shortcut}</p>}
            {footer}
            <p className={styles.saveHint}>按 ⌘ S 保存</p>
          </article>
          {headings.length > 0 && (
            <aside className={styles.outline} aria-label="文章目录">
              <strong>目录</strong>
              <nav>
                {headings.map((heading) => (
                  <button
                    key={heading.id}
                    type="button"
                    className={styles[`outlineLevel${heading.level}`]}
                    onClick={() => locateHeading(heading)}
                    title={privacy.enabled ? undefined : heading.label}
                    data-writing-private="true"
                  >
                    {heading.label}
                  </button>
                ))}
              </nav>
            </aside>
          )}
        </div>
      </div>
    </main>
  );
}

