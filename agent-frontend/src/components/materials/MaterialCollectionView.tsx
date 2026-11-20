import { useMemo } from 'react';
import { FolderOpenIcon, TrashIcon } from '@phosphor-icons/react';
import { materialContentUrl } from '../../services/materialApi';
import type { ChatMaterialItem } from '../../types/material';
import {
  channelLabel,
  formatMaterialBytes,
  formatMaterialDateTime,
  groupMaterialsByDate,
  isImageMaterial,
} from './materialsPresentation';
import styles from './MaterialsPage.module.css';

interface MaterialCollectionViewProps {
  items: ChatMaterialItem[];
  viewMode: 'grid' | 'list';
  onPreview: (item: ChatMaterialItem) => void;
  onDelete: (item: ChatMaterialItem) => void;
}

export function MaterialCollectionView({ items, viewMode, onPreview, onDelete }: MaterialCollectionViewProps) {
  const groups = useMemo(() => groupMaterialsByDate(items), [items]);

  if (viewMode === 'list') {
    return (
      <div className={styles.groups}>
        {groups.map((group) => (
          <section key={group.dateKey} className={styles.dateGroup} aria-label={group.label}>
            <header className={styles.groupHeader}>
              <h2 className={styles.groupTitle}>{group.label}</h2>
              <span className={styles.groupCount}>{group.items.length}</span>
            </header>
            <div className={styles.list}>
              {group.items.map((item) => (
                <article key={item.fileId} className={styles.listRow}>
                  <button
                    type="button"
                    className={styles.listThumb}
                    onClick={() => onPreview(item)}
                    aria-label={`查看 ${item.originalName}`}
                  >
                    {isImageMaterial(item) ? (
                      <img src={materialContentUrl(item.fileId)} alt={item.originalName} loading="lazy" />
                    ) : (
                      <FolderOpenIcon size={16} />
                    )}
                  </button>
                  <div className={styles.listMain}>
                    <button
                      type="button"
                      className={styles.listTitle}
                      onClick={() => onPreview(item)}
                      title={item.originalName}
                    >
                      {item.originalName}
                    </button>
                    <div className={styles.listSub}>
                      <span className={styles.listSession} title={item.sessionTitle}>
                        {item.sessionTitle || '新对话'}
                      </span>
                      <span className={styles.dot} aria-hidden="true">·</span>
                      <span>{channelLabel(item.channel)}</span>
                      <span className={styles.dot} aria-hidden="true">·</span>
                      <span>{formatMaterialDateTime(item.createdAt)}</span>
                    </div>
                  </div>
                  <div className={styles.listRight}>
                    <span className={styles.listFormat}>{item.subType}</span>
                    <span className={styles.listSize}>{formatMaterialBytes(item.sizeBytes)}</span>
                    <button
                      type="button"
                      className={styles.listDeleteBtn}
                      aria-label={`删除 ${item.originalName}`}
                      title="删除档案"
                      onClick={() => onDelete(item)}
                    >
                      <TrashIcon size={14} />
                    </button>
                  </div>
                </article>
              ))}
            </div>
          </section>
        ))}
      </div>
    );
  }

  return (
    <div className={styles.groups}>
      {groups.map((group) => (
        <section key={group.dateKey} className={styles.dateGroup} aria-label={group.label}>
          <header className={styles.groupHeader}>
            <h2 className={styles.groupTitle}>{group.label}</h2>
            <span className={styles.groupCount}>{group.items.length}</span>
          </header>
          <div className={styles.grid}>
            {group.items.map((item) => {
              const sourceLabel = item.channel?.toUpperCase() === 'FEISHU'
                ? `飞书 · ${item.sessionTitle || '新对话'}`
                : (item.sessionTitle || '新对话');

              return (
                <article key={item.fileId} className={styles.card}>
                  <div className={styles.thumbFrame}>
                    <button
                      type="button"
                      className={styles.thumbBtn}
                      aria-label={`查看 ${item.originalName}`}
                      onClick={() => onPreview(item)}
                    >
                      {isImageMaterial(item) ? (
                        <img
                          className={styles.thumbImg}
                          src={materialContentUrl(item.fileId)}
                          alt={item.originalName}
                          loading="lazy"
                        />
                      ) : (
                        <FolderOpenIcon size={24} />
                      )}
                    </button>
                    <button
                      type="button"
                      className={styles.cardDeleteBtn}
                      aria-label={`删除 ${item.originalName}`}
                      title="删除档案"
                      onClick={() => onDelete(item)}
                    >
                      <TrashIcon size={13} />
                    </button>
                  </div>
                  <div className={styles.cardCaption}>
                    <button
                      type="button"
                      className={styles.cardTitle}
                      onClick={() => onPreview(item)}
                      title={item.originalName}
                    >
                      {item.originalName}
                    </button>
                    <div className={styles.cardMeta}>
                      <span className={styles.cardSession} title={sourceLabel}>
                        {sourceLabel}
                      </span>
                      <span className={styles.cardSize}>{formatMaterialBytes(item.sizeBytes)}</span>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      ))}
    </div>
  );
}
