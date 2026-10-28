import React, { useEffect, useId, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Check, Copy, Maximize2, Minus, Plus, X } from 'lucide-react';
import { CodeBlock } from '@/components/ui/code-block';
import styles from './MermaidBlock.module.css';

type MermaidApi = (typeof import('mermaid'))['default'];
type RenderResult = Awaited<ReturnType<MermaidApi['render']>>;

let mermaidPromise: Promise<MermaidApi> | null = null;
let renderQueue: Promise<void> = Promise.resolve();
let renderCount = 0;

/** Mermaid 体积较大，仅在消息确实包含图表时加载。 */
async function loadMermaid(): Promise<MermaidApi> {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        suppressErrorRendering: true,
        theme: 'base',
        fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, sans-serif',
        themeVariables: {
          primaryColor: '#eff6ff',
          primaryBorderColor: '#93c5fd',
          primaryTextColor: '#0f172a',
          lineColor: '#64748b',
          secondaryColor: '#f8fafc',
          tertiaryColor: '#ffffff',
          clusterBkg: '#f8fafc',
          clusterBorder: '#cbd5e1',
          fontSize: '14px',
        },
      });
      return mermaid;
    });
  }
  return mermaidPromise;
}

/** Mermaid 使用共享配置，串行渲染可避免多张图同时更新内部状态。 */
function renderMermaid(id: string, source: string): Promise<RenderResult> {
  const task = renderQueue.then(async () => {
    const mermaid = await loadMermaid();
    return mermaid.render(id, source);
  });
  renderQueue = task.then(
    () => undefined,
    () => undefined
  );
  return task;
}

/** 容忍模型偶尔输出的 Unicode 箭头简写，例如 `A → B`。 */
function normalizeMermaidSource(source: string): string {
  return source.trim().replace(/[ \t]+(?:→|⟶|⟹)[ \t]+/g, ' --> ');
}

export interface MermaidBlockProps {
  code: string;
}

const MIN_SCALE = 0.5;
const MAX_SCALE = 3;
const SCALE_STEP = 0.25;

function clampScale(value: number): number {
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, value));
}

interface DiagramToolbarProps {
  copied: boolean;
  expanded: boolean;
  scale: number;
  onCopy: () => void;
  onExpand: () => void;
  onScaleChange: (scale: number) => void;
}

const DiagramToolbar: React.FC<DiagramToolbarProps> = ({
  copied,
  expanded,
  scale,
  onCopy,
  onExpand,
  onScaleChange,
}) => (
  <div className={styles.toolbar}>
    <button
      type="button"
      className={styles.toolButton}
      onClick={() => onScaleChange(scale - SCALE_STEP)}
      disabled={scale <= MIN_SCALE}
      title="缩小"
      aria-label="缩小图表"
    >
      <Minus size={15} />
    </button>
    <button
      type="button"
      className={styles.scaleButton}
      onClick={() => onScaleChange(1)}
      title="恢复为 100%"
      aria-label={`当前缩放 ${Math.round(scale * 100)}%，点击恢复为 100%`}
    >
      {Math.round(scale * 100)}%
    </button>
    <button
      type="button"
      className={styles.toolButton}
      onClick={() => onScaleChange(scale + SCALE_STEP)}
      disabled={scale >= MAX_SCALE}
      title="放大"
      aria-label="放大图表"
    >
      <Plus size={15} />
    </button>
    <button
      type="button"
      className={styles.toolButton}
      onClick={onExpand}
      title={expanded ? '退出大图查看' : '放大查看'}
      aria-label={expanded ? '退出大图查看' : '放大查看图表'}
    >
      {expanded ? <X size={16} /> : <Maximize2 size={15} />}
    </button>
    <button
      type="button"
      className={styles.toolButton}
      onClick={onCopy}
      title={copied ? '已复制' : '复制图表源码'}
      aria-label={copied ? '图表源码已复制' : '复制图表源码'}
    >
      {copied ? <Check size={15} /> : <Copy size={15} />}
    </button>
  </div>
);

/** 将 Markdown 的 mermaid 代码围栏渲染为图表，失败时回退显示原始源码。 */
export const MermaidBlock: React.FC<MermaidBlockProps> = ({ code }) => {
  const reactId = useId();
  const diagramId = useMemo(() => `mermaid-${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}`, [reactId]);
  const source = useMemo(() => normalizeMermaidSource(code), [code]);
  const [svg, setSvg] = useState('');
  const [error, setError] = useState(false);
  const [copied, setCopied] = useState(false);
  const [scale, setScale] = useState(1);
  const [expanded, setExpanded] = useState(false);
  const expandedRef = useRef<HTMLDivElement>(null);
  const diagramRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let active = true;
    setSvg('');
    setError(false);

    const currentRenderId = `${diagramId}-${++renderCount}`;
    renderMermaid(currentRenderId, source)
      .then((result) => {
        if (active) setSvg(result.svg);
      })
      .catch(() => {
        if (active) setError(true);
      });

    return () => {
      active = false;
    };
  }, [diagramId, source]);

  useEffect(() => {
    if (!expanded) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    expandedRef.current?.querySelector<HTMLElement>('button')?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setExpanded(false);
        return;
      }
      if (event.key !== 'Tab') return;

      const focusable = Array.from(
        expandedRef.current?.querySelectorAll<HTMLElement>('button:not(:disabled)') ?? []
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      previousFocus?.focus();
    };
  }, [expanded]);

  useEffect(() => {
    const diagram = diagramRef.current;
    if (!diagram) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      setScale((current) =>
        clampScale(current + (event.deltaY < 0 ? SCALE_STEP : -SCALE_STEP))
      );
    };
    diagram.addEventListener('wheel', onWheel, { passive: false });
    return () => diagram.removeEventListener('wheel', onWheel);
  }, [expanded]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      // 剪贴板不可用时保留图表，不影响阅读。
    }
  };

  const handleScaleChange = (nextScale: number) => {
    setScale(clampScale(nextScale));
  };

  const renderDiagram = (isExpanded: boolean) => (
    <div
      ref={diagramRef}
      className={`${styles.diagram} ${isExpanded ? styles.expandedDiagram : ''} ${svg ? styles.ready : styles.loading}`}
      aria-busy={!svg}
      aria-live="polite"
      title="按住 Ctrl 或 Command 并滚动鼠标缩放"
    >
      {svg ? (
        <div
          className={styles.svgWrap}
          style={{ width: `${scale * 100}%` }}
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      ) : (
        <span className={styles.loadingText}>正在绘制图表…</span>
      )}
    </div>
  );

  if (error) {
    return (
      <div className={styles.fallback}>
        <p role="alert">Mermaid 图表语法有误，已显示原始内容。</p>
        <CodeBlock language="mermaid" code={code} />
      </div>
    );
  }

  return (
    <>
      <figure className={styles.card} aria-label="Mermaid 图表">
        <figcaption className={styles.header}>
          <span className={styles.label}>Mermaid</span>
          <DiagramToolbar
            copied={copied}
            expanded={false}
            scale={scale}
            onCopy={handleCopy}
            onExpand={() => setExpanded(true)}
            onScaleChange={handleScaleChange}
          />
        </figcaption>
        {!expanded && renderDiagram(false)}
      </figure>

      {expanded &&
        createPortal(
          <div
            ref={expandedRef}
            className={styles.overlay}
            role="dialog"
            aria-modal="true"
            aria-label="Mermaid 大图查看"
            onMouseDown={(event) => {
              if (event.target === event.currentTarget) setExpanded(false);
            }}
          >
            <div className={styles.expandedCard}>
              <div className={styles.expandedHeader}>
                <span className={styles.label}>Mermaid</span>
                <DiagramToolbar
                  copied={copied}
                  expanded
                  scale={scale}
                  onCopy={handleCopy}
                  onExpand={() => setExpanded(false)}
                  onScaleChange={handleScaleChange}
                />
              </div>
              {renderDiagram(true)}
            </div>
          </div>,
          document.body
        )}
    </>
  );
};

export default MermaidBlock;
