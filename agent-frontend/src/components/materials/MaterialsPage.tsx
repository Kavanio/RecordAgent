import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowsClockwiseIcon,
  FolderOpenIcon,
  ImageIcon,
  ListBulletsIcon,
  MagnifyingGlassIcon,
  SquaresFourIcon,
} from '@phosphor-icons/react';
import { deleteMaterial, fetchMaterials, fetchMaterialSummary } from '../../services/materialApi';
import type { ChatMaterialItem, ChatMaterialSummary } from '../../types/material';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { useMessage } from '../common/Message';
import { Select } from '../common/Select';
import { Skeleton } from '../common/Skeleton';
import { TopBar } from '../common/TopBar';
import { TopBarAction } from '../common/TopBarAction';
import { MaterialCollectionView } from './MaterialCollectionView';
import { MaterialPreviewModal } from './MaterialPreviewModal';
import { formatMaterialBytes } from './materialsPresentation';
import styles from './MaterialsPage.module.css';

interface MaterialsPageProps {
  existingSessionIds: Set<string>;
  onOpenSession: (sessionId: string) => void;
}

const PAGE_SIZE = 40;

export function MaterialsPage({ existingSessionIds, onOpenSession }: MaterialsPageProps) {
  const { showMessage } = useMessage();
  const [items, setItems] = useState<ChatMaterialItem[]>([]);
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState<ChatMaterialSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [query, setQuery] = useState('');
  const [channel, setChannel] = useState('');
  const [sessionKind, setSessionKind] = useState('');
  const [subType, setSubType] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');

  const [previewItem, setPreviewItem] = useState<ChatMaterialItem | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ChatMaterialItem | null>(null);
  const [deleting, setDeleting] = useState(false);
  const requestSeq = useRef(0);

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    setLoading(true);
    setError(null);
    try {
      const [page, sum] = await Promise.all([
        fetchMaterials({
          channel: channel || undefined,
          sessionKind: sessionKind || undefined,
          subType: subType || undefined,
          sessionId: sessionId || undefined,
          query: query.trim() || undefined,
          limit: PAGE_SIZE,
          offset: 0,
        }),
        fetchMaterialSummary(),
      ]);
      if (seq !== requestSeq.current) return;
      setItems(page.items);
      setTotal(page.total);
      setSummary(sum);
    } catch (reason) {
      if (seq === requestSeq.current) {
        setError(reason instanceof Error ? reason.message : '档案加载失败');
      }
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [channel, query, sessionId, sessionKind, subType]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), query ? 220 : 0);
    return () => {
      window.clearTimeout(timer);
      requestSeq.current += 1;
    };
  }, [load, query]);

  const loadMore = async () => {
    if (loadingMore || items.length >= total) return;
    setLoadingMore(true);
    try {
      const page = await fetchMaterials({
        channel: channel || undefined,
        sessionKind: sessionKind || undefined,
        subType: subType || undefined,
        sessionId: sessionId || undefined,
        query: query.trim() || undefined,
        limit: PAGE_SIZE,
        offset: items.length,
      });
      setItems((prev) => [...prev, ...page.items]);
      setTotal(page.total);
    } catch (reason) {
      showMessage('error', reason instanceof Error ? reason.message : '加载更多失败');
    } finally {
      setLoadingMore(false);
    }
  };

  const handleConfirmDelete = async () => {
    if (!deleteTarget) return;
    setDeleting(true);
    try {
      await deleteMaterial(deleteTarget.fileId);
      if (previewItem?.fileId === deleteTarget.fileId) setPreviewItem(null);
      setDeleteTarget(null);
      showMessage('success', '档案素材已删除');
      await load();
    } catch (reason) {
      showMessage('error', reason instanceof Error ? reason.message : '删除失败');
    } finally {
      setDeleting(false);
    }
  };

  const hasFilters = Boolean(channel || sessionKind || subType || sessionId || query.trim());
  const sessionOptions = useMemo(() => [
    { value: '', label: '全部对话' },
    ...(summary?.sessions ?? []).map((bucket) => ({
      value: bucket.sessionId,
      label: `${bucket.sessionTitle || '新对话'} (${bucket.count})`,
    })),
  ], [summary]);

  const clearFilters = () => {
    setQuery('');
    setChannel('');
    setSessionKind('');
    setSubType('');
    setSessionId('');
  };

  return (
    <main className={styles.workspace}>
      <TopBar
        title="档案"
        subtitle="对话素材归档"
        icon={<FolderOpenIcon size={16} />}
        search={
          <div className={styles.search}>
            <MagnifyingGlassIcon size={15} />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              aria-label="搜索档案"
              placeholder="搜索文件名、对话标题、ID 或归档路径…"
            />
          </div>
        }
        actions={
          <>
            <TopBarAction
              iconOnly
              aria-label={viewMode === 'grid' ? '切换列表视图' : '切换网格视图'}
              title={viewMode === 'grid' ? '切换列表视图' : '切换网格视图'}
              onClick={() => setViewMode((prev) => (prev === 'grid' ? 'list' : 'grid'))}
            >
              {viewMode === 'grid' ? <ListBulletsIcon size={15} /> : <SquaresFourIcon size={15} />}
            </TopBarAction>
            <TopBarAction
              iconOnly
              aria-label="刷新档案"
              title="刷新档案"
              onClick={() => void load()}
            >
              <ArrowsClockwiseIcon size={15} />
            </TopBarAction>
          </>
        }
      />

      <div className={styles.content}>
        <section className={styles.summaryBar} aria-label="档案存储概览" aria-busy={loading}>
          {loading && !summary ? (
            <div className={styles.summaryLoading}>
              <Skeleton variant="text" decorative />
              <Skeleton variant="text" decorative />
              <Skeleton variant="text" decorative />
            </div>
          ) : summary ? (
            <>
              <div className={styles.statCell}>
                <strong>{summary.totalCount}</strong>
                <span>已归档文件</span>
              </div>
              <div className={styles.statCell}>
                <strong>{formatMaterialBytes(summary.totalBytes)}</strong>
                <span>本地存储占用</span>
              </div>
              <div className={styles.statCell}>
                <strong>{summary.byCategory.IMAGE ?? 0}</strong>
                <span>图片资料</span>
              </div>
              <div className={styles.statCell}>
                <strong>{summary.sessions.length}</strong>
                <span>关联对话数</span>
              </div>
              <p className={styles.storageHint}>
                按渠道 / 会话类型 / 会话 ID / 格式 / 日期自动分类归档于 <code>.butvan-agent/materials/</code>
              </p>
            </>
          ) : (
            <span className={styles.summaryUnavailable}>档案概览暂不可用</span>
          )}
        </section>

        <section className={styles.filterBar} aria-label="档案筛选">
          <div className={styles.filterGroup}>
            <Select
              aria-label="来源渠道"
              fieldSize="sm"
              value={channel}
              onChange={(event) => setChannel(event.target.value)}
              options={[
                { value: '', label: '全部渠道' },
                { value: 'DESKTOP', label: '桌面端' },
                { value: 'FEISHU', label: '飞书' },
              ]}
            />
            <Select
              aria-label="会话类型"
              fieldSize="sm"
              value={sessionKind}
              onChange={(event) => setSessionKind(event.target.value)}
              options={[
                { value: '', label: '全部类型' },
                { value: 'GENERAL', label: '普通对话' },
                { value: 'PROJECT', label: '项目对话' },
              ]}
            />
            <Select
              aria-label="文件格式"
              fieldSize="sm"
              value={subType}
              onChange={(event) => setSubType(event.target.value)}
              options={[
                { value: '', label: '全部格式' },
                { value: 'PNG', label: 'PNG' },
                { value: 'JPEG', label: 'JPEG' },
                { value: 'WEBP', label: 'WEBP' },
                { value: 'GIF', label: 'GIF' },
              ]}
            />
            <Select
              aria-label="所属对话"
              fieldSize="sm"
              value={sessionId}
              onChange={(event) => setSessionId(event.target.value)}
              options={sessionOptions}
            />
          </div>
          <div className={styles.filterRight}>
            <span className={styles.countText}>共 {total} 项</span>
            {hasFilters && (
              <Button size="sm" variant="ghost" onClick={clearFilters}>
                重置筛选
              </Button>
            )}
          </div>
        </section>

        {error && (
          <div className={styles.errorBanner} role="alert">
            <span>{error}</span>
            <Button size="sm" variant="outline" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        )}

        {loading && items.length === 0 ? (
          <div className={styles.skeletonWrap}>
            <Skeleton variant="document" label="正在整理对话档案…" />
          </div>
        ) : items.length === 0 ? (
          <div className={styles.emptyState}>
            <ImageIcon size={24} />
            <strong>{hasFilters ? '没有符合筛选条件的档案' : '暂无已保存的对话档案'}</strong>
            <span>
              {hasFilters
                ? '尝试调整渠道、会话或格式筛选条件。'
                : '在对话中向 Agent 发送图片后，系统会自动生成唯一 ID 并按分类目录保存于此。'}
            </span>
            {hasFilters && (
              <Button size="sm" variant="primary" onClick={clearFilters}>
                查看全部档案
              </Button>
            )}
          </div>
        ) : (
          <MaterialCollectionView
            items={items}
            viewMode={viewMode}
            onPreview={setPreviewItem}
            onDelete={setDeleteTarget}
          />
        )}

        {items.length < total && (
          <div className={styles.loadMoreRow}>
            <Button variant="outline" size="sm" disabled={loadingMore} onClick={() => void loadMore()}>
              {loadingMore ? '加载中…' : `加载更多（已显示 ${items.length} / ${total}）`}
            </Button>
          </div>
        )}
      </div>

      <MaterialPreviewModal
        item={previewItem}
        canOpenSession={Boolean(previewItem && existingSessionIds.has(previewItem.sessionId))}
        onClose={() => setPreviewItem(null)}
        onOpenSession={onOpenSession}
        onRequestDelete={(item) => setDeleteTarget(item)}
      />

      <ConfirmDialog
        open={Boolean(deleteTarget)}
        title="确认删除档案素材"
        tone="danger"
        confirmText="永久删除"
        confirmVariant="danger"
        busy={deleting}
        description={`确定要永久删除“${deleteTarget?.originalName ?? ''}”吗？本地归档文件与数据库记录将一并清理，无法恢复。`}
        onClose={() => { if (!deleting) setDeleteTarget(null); }}
        onConfirm={() => void handleConfirmDelete()}
      />
    </main>
  );
}
