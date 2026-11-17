import { FolderOpenIcon, TrashIcon } from '@phosphor-icons/react';
import { materialContentUrl } from '../../services/materialApi';
import type { ChatMaterialItem } from '../../types/material';
import { Badge } from '../common/Badge';
import {
  channelLabel,
  formatMaterialBytes,
  formatMaterialDateTime,
  isImageMaterial,
  sessionKindLabel,
  shortFileId,
} from './materialsPresentation';
import styles from './MaterialsPage.module.css';

interface MaterialCollectionViewProps {
  items: ChatMaterialItem[];
  viewMode: 'grid' | 'list';
  onPreview: (item: ChatMaterialItem) => void;
  onDelete: (item: ChatMaterialItem) => void;
}

export function MaterialCollectionView({ items, viewMode, onPreview, onDelete }: MaterialCollectionViewProps) {
  if (viewMode === 'list') {
    return (
      <div className={styles.list}>
        {items.map((item) => (
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
                <FolderOpenIcon size={18} />
              )}
            </button>
            <div className={styles.listMain}>
              <div className={styles.listTitleRow}>
                <button type="button" className={styles.cardTitle} onClick={() => onPreview(item)}>
                  {item.originalName}
                </button>
                <Badge variant="default">{item.subType}</Badge>
                <Badge variant={item.channel === 'FEISHU' ? 'purple' : 'primary'}>
                  {channelLabel(item.channel)}
                </Badge>
                <Badge variant="default">{sessionKindLabel(item.sessionKind)}</Badge>
              </div>
              <div className={styles.listSub}>
                <span>对话：{item.sessionTitle}</span>
                <span>·</span>
                <span>ID：{shortFileId(item.fileId)}</span>
                <span>·</span>
                <span>{formatMaterialBytes(item.sizeBytes)}</span>
                <span>·</span>
                <span>{formatMaterialDateTime(item.createdAt)}</span>
              </div>
              <div className={styles.listArchivePath} title={`.butvan-agent/materials/${item.archivePath}`}>
                .butvan-agent/materials/{item.archivePath}
              </div>
            </div>
            <button
              type="button"
              className={styles.deleteIconBtn}
              aria-label={`删除 ${item.originalName}`}
              onClick={() => onDelete(item)}
            >
              <TrashIcon size={15} />
            </button>
          </article>
        ))}
      </div>
    );
  }

  return (
    <div className={styles.grid}>
      {items.map((item) => (
        <article key={item.fileId} className={styles.card}>
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
              <FolderOpenIcon size={28} />
            )}
          </button>
          <div className={styles.cardBody}>
            <div className={styles.cardTitleRow}>
              <button
                type="button"
                className={styles.cardTitle}
                onClick={() => onPreview(item)}
                title={item.originalName}
              >
                {item.originalName}
              </button>
              <button
                type="button"
                className={styles.deleteIconBtn}
                aria-label={`删除 ${item.originalName}`}
                onClick={() => onDelete(item)}
              >
                <TrashIcon size={14} />
              </button>
            </div>
            <div className={styles.cardBadges}>
              <Badge variant="default">{item.subType}</Badge>
              <Badge variant={item.channel === 'FEISHU' ? 'purple' : 'primary'}>
                {channelLabel(item.channel)}
              </Badge>
              <Badge variant="default">{sessionKindLabel(item.sessionKind)}</Badge>
            </div>
            <div className={styles.cardMeta} title={item.sessionTitle}>
              <span>{item.sessionTitle}</span>
              <span>{formatMaterialBytes(item.sizeBytes)}</span>
            </div>
            <div className={styles.cardPath} title={`.butvan-agent/materials/${item.archivePath}`}>
              ID: {shortFileId(item.fileId)} · {item.createdDate}
            </div>
          </div>
        </article>
      ))}
    </div>
  );
}
