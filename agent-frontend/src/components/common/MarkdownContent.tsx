import React, { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { convertFileSrc } from '@tauri-apps/api/core';
import { createPortal } from 'react-dom';
import { X } from 'lucide-react';
import { CodeBlock } from '@/components/ui/code-block';
import { MermaidBlock } from './MermaidBlock';
import styles from './MarkdownContent.module.css';

/** 从 ReactNode 中递归提取纯文本（用于复制代码）。 */
function extractCodeText(node: React.ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(extractCodeText).join('');
  if (React.isValidElement<{ children?: React.ReactNode }>(node)) {
    return extractCodeText(node.props.children);
  }
  return '';
}

function isTauriRuntime(): boolean {
  return typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;
}

/**
 * 将 Markdown 图片地址转换为可展示的 URL：
 * - http(s)/data/blob 等网络与内联链接直接放行；
 * - file:// 或本地绝对路径（macOS/Linux `/…`、Windows `C:\…`）映射为 Tauri asset 协议地址。
 */
function resolveImageSrc(src: string): string {
  const trimmed = src.trim();
  if (/^(https?:|data:|blob:)/i.test(trimmed)) return trimmed;

  // micromark 会百分号编码链接目标（空格、非 ASCII、反斜杠等），先解码还原真实文件路径
  let localPath = trimmed;
  try {
    localPath = decodeURIComponent(localPath);
  } catch {
    // 非合法编码时保留原样
  }

  if (/^file:\/\//i.test(trimmed)) {
    try {
      localPath = decodeURIComponent(new URL(trimmed).pathname);
    } catch {
      localPath = trimmed.replace(/^file:\/\/+/i, '/');
    }
  }

  // Windows 盘符路径：file:///C:/… 提取后去掉开头的 /
  localPath = localPath.replace(/^\/+([A-Za-z]:[\\/])/, '$1');

  const isLocalPath = /^\//.test(localPath) && !/^\/\//.test(localPath);
  const isWindowsPath = /^[A-Za-z]:[\\/]/.test(localPath);
  if (!isLocalPath && !isWindowsPath) return trimmed;

  // 桌面端（Tauri）通过 asset 协议读取本地文件；浏览器开发环境无法直接读取，保持原样。
  if (isTauriRuntime()) {
    try {
      return convertFileSrc(localPath);
    } catch {
      return trimmed;
    }
  }
  return trimmed;
}

/** Markdown 代码围栏 → 复用 CodeBlock 卡片组件。 */
const PreBlock: React.FC<React.ComponentPropsWithoutRef<'pre'>> = ({ children }) => {
  const codeChild = React.Children.toArray(children).find(
    (child): child is React.ReactElement<{ className?: string; children?: React.ReactNode }> =>
      React.isValidElement(child) && child.type === 'code'
  );
  const language = (codeChild?.props.className || '').match(/language-([\w-]+)/)?.[1] || undefined;
  const codeText = codeChild ? extractCodeText(codeChild.props.children) : '';
  if (language?.toLowerCase() === 'mermaid') {
    return <MermaidBlock code={codeText} />;
  }
  return <CodeBlock language={language} code={codeText} />;
};

/** 表格：外层横向滚动容器，表头吸顶。 */
const TableBlock: React.FC<React.ComponentPropsWithoutRef<'table'>> = ({ children, ...props }) => (
  <div className={styles.tableWrap}>
    <table {...props}>{children}</table>
  </div>
);

/**
 * 容错清洗：修复模型输出中被压缩在同一行的 Markdown 表格。
 */
function normalizeMarkdown(raw?: string): string {
  if (!raw) return '';

  const blocks = raw.split(/(```[\s\S]*?```)/g);
  return blocks
    .map((block, idx) => {
      if (idx % 2 === 1) return block;

      const res = block.replace(/\|\s*\|\s*(?=[^\n]*\|)/g, '|\n|');
      const lines = res.split(/\r?\n/);
      const output: string[] = [];
      let inTable = false;

      for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const trimmed = line.trim();
        const isTableLine = trimmed.startsWith('|') && trimmed.endsWith('|');

        if (isTableLine) {
          if (!inTable) {
            if (output.length > 0 && output[output.length - 1].trim() !== '') {
              output.push('');
            }
            inTable = true;
          }
          output.push(trimmed);
        } else {
          if (inTable) {
            if (trimmed !== '') {
              output.push('');
            }
            inTable = false;
          }
          output.push(line);
        }
      }

      return output.join('\n');
    })
    .join('');
}

export interface MarkdownContentProps {
  content?: string;
  className?: string;
}

/**
 * 统一 Markdown 渲染：GFM + 代码语法高亮 + Mermaid 图表。
 */
export const MarkdownContent: React.FC<MarkdownContentProps> = ({
  content,
  className = '',
}) => {
  const [previewSrc, setPreviewSrc] = useState<string | null>(null);

  // 图片预览：Esc 关闭
  useEffect(() => {
    if (!previewSrc) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setPreviewSrc(null);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [previewSrc]);

  return (
    <div className={`${styles.markdown} ${className}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        urlTransform={(url, key) => {
          // 图片 src：放行 file/data/本地盘符等，由 resolveImageSrc 统一处理
          if (key === 'src') return url;
          // 相对链接直接放行；绝对链接仅保留安全的网络/邮件/电话协议
          const scheme = /^([a-z][a-z0-9+.-]*):/i.exec(url);
          if (!scheme) return url;
          return /^(https?|mailto|tel):/i.test(url) ? url : '';
        }}
        components={{
          pre: PreBlock,
          table: TableBlock,
          img: (props) => {
            const src = resolveImageSrc(props.src || '');
            return (
              <img
                {...props}
                src={src}
                onClick={() => setPreviewSrc(src)}
                alt={props.alt || '图片'}
                title="点击查看大图"
              />
            );
          },
        }}
      >
        {normalizeMarkdown(content)}
      </ReactMarkdown>

      {/* 图片详情预览（Lightbox） */}
      {previewSrc &&
        createPortal(
          <div
            className={styles.lightbox}
            role="dialog"
            aria-modal="true"
            aria-label="图片预览"
            onClick={() => setPreviewSrc(null)}
          >
            <img
              src={previewSrc}
              alt="图片预览"
              className={styles.lightboxImg}
              onClick={(e) => e.stopPropagation()}
            />
            <button
              type="button"
              className={styles.lightboxClose}
              onClick={() => setPreviewSrc(null)}
              aria-label="关闭预览"
              title="关闭预览 (Esc)"
            >
              <X size={18} />
            </button>
          </div>,
          document.body
        )}
    </div>
  );
};
