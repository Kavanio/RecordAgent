import { ArrowSquareOutIcon, CopyIcon, DownloadSimpleIcon, TrashIcon } from '@phosphor-icons/react';
import { materialContentUrl } from '../../services/materialApi';
import type { ChatMaterialItem } from '../../types/material';
import { Badge } from '../common/Badge';
import { Button } from '../common/Button';
import { Modal } from '../common/Modal';
import { useMessage } from '../common/Message';
import {
  categoryLabel,
  channelLabel,
  formatMaterialBytes,
  formatMaterialDateTime,
  isImageMaterial,
  sessionKindLabel,
} from './materialsPresentation';
import styles from './MaterialPreviewModal.module.css';

interface MaterialPreviewModalProps {
  item: ChatMaterialItem | null;
  canOpenSession: boolean;
  onClose: () => void;
  onOpenSession?: (sessionId: string) => void;
  onRequestDelete: (item: ChatMaterialItem) => void;
}

export function MaterialPreviewModal({
  item,
  canOpenSession,
  onClose,
  onOpenSession,
  onRequestDelete,
}: MaterialPreviewModalProps) {
  const { showMessage } = useMessage();

  if (!item) return null;

  const copyText = async (value: string, label: string) => {
    try {
      await navigator.clipboard.writeText(value);
      showMessage('success', `已复制${label}`);
    } catch {
      showMessage('error', `复制${label}失败`);
    }
  };

  return (
    <Modal
      open
      title={item.originalName || '档案详情'}
      onClose={onClose}
      width={720}
      centered
      footer={
        <div className={styles.footerBar}>
          <Button
            variant="danger"
            size="sm"
            icon={<TrashIcon size={14} />}
            onClick={() => onRequestDelete(item)}
          >
            删除档案
          </Button>
          <div className={styles.footerRight}>
            {canOpenSession && onOpenSession && (
              <Button
                variant="outline"
                size="sm"
                icon={<ArrowSquareOutIcon size={14} />}
                onClick={() => {
                  onClose();
                  onOpenSession(item.sessionId);
                }}
              >
                打开原对话
              </Button>
            )}
            <a
              className={styles.downloadLink}
              href={materialContentUrl(item.fileId, true)}
              download={item.originalName}
            >
              <Button variant="primary" size="sm" icon={<DownloadSimpleIcon size={14} />}>
                下载原文件
              </Button>
            </a>
          </div>
        </div>
      }
    >
      <div className={styles.container}>
        {isImageMaterial(item) && (
          <div className={styles.previewStage}>
            <img
              className={styles.previewImg}
              src={materialContentUrl(item.fileId)}
              alt={item.originalName}
            />
          </div>
        )}

        <div className={styles.badges}>
          <Badge variant="primary">{categoryLabel(item.category)}</Badge>
          <Badge variant="default">{item.subType}</Badge>
          <Badge variant={item.channel === 'FEISHU' ? 'purple' : 'success'}>
            {channelLabel(item.channel)}
          </Badge>
          <Badge variant="default">{sessionKindLabel(item.sessionKind)}</Badge>
        </div>

        <dl className={styles.metaGrid}>
          <div className={styles.metaRow}>
            <dt>唯一文件 ID</dt>
            <dd className={styles.mono}>
              <span>{item.fileId}</span>
              <button
                type="button"
                className={styles.copyBtn}
                aria-label="复制文件 ID"
                onClick={() => void copyText(item.fileId, '文件 ID')}
              >
                <CopyIcon size={13} />
              </button>
            </dd>
          </div>
          <div className={styles.metaRow}>
            <dt>分类归档路径</dt>
            <dd className={styles.mono}>
              <span>.butvan-agent/materials/{item.archivePath}</span>
              <button
                type="button"
                className={styles.copyBtn}
                aria-label="复制归档路径"
                onClick={() => void copyText(`.butvan-agent/materials/${item.archivePath}`, '归档路径')}
              >
                <CopyIcon size={13} />
              </button>
            </dd>
          </div>
          <div className={styles.metaRow}>
            <dt>所属对话</dt>
            <dd>{item.sessionTitle}{!canOpenSession ? '（原会话已清理）' : ''}</dd>
          </div>
          <div className={styles.metaRow}>
            <dt>大小与类型</dt>
            <dd>{formatMaterialBytes(item.sizeBytes)} · {item.mediaType}</dd>
          </div>
          <div className={styles.metaRow}>
            <dt>存入时间</dt>
            <dd>{formatMaterialDateTime(item.createdAt)}</dd>
          </div>
          {item.sha256 && (
            <div className={styles.metaRow}>
              <dt>SHA-256</dt>
              <dd className={styles.mono}>{item.sha256}</dd>
            </div>
          )}
        </dl>
      </div>
    </Modal>
  );
}
