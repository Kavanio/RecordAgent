import { useState } from 'react';
import type { ChatImageAttachment } from '../../types/chat';
import { findSlashCommand, parseSlashCommand } from '../../features/slash-command/slashCommands';
import { parseSlashCommandDisplayArguments } from '../../features/slash-command/slashCommandDisplay';
import { Modal } from '../common/Modal';
import { RecordReferenceTag } from './RecordReferenceTag';
import { SlashCommandChip } from './SlashCommandChip';
import styles from './UserMessageContent.module.css';

/** 用户消息统一展示：资料引用、命令和图片可以并存，不因展示分支隐藏附件。 */
export function UserMessageContent({ content, images = [] }: { content: string; images?: ChatImageAttachment[] }) {
  const [failedKeys, setFailedKeys] = useState<Record<string, boolean>>({});
  const [preview, setPreview] = useState<ChatImageAttachment | null>(null);
  const parsed = parseSlashCommand(content);
  const command = parsed ? findSlashCommand(parsed.name) : undefined;
  const composed = command?.presentation.selection === 'COMPOSE';
  const display = parseSlashCommandDisplayArguments(composed && parsed ? parsed.args : content);
  return (
    <div className={styles.userMessage}>
      {images.length > 0 && (
        <div className={styles.images}>
          {images.map((image, index) => {
            const key = image.fileId || `${image.name || 'image'}-${index}`;
            const label = image.name || `图片 ${index + 1}`;
            if (failedKeys[key]) {
              return <div key={key} className={styles.missingImage}>图片已移除</div>;
            }
            return (
              <button
                key={key}
                type="button"
                className={styles.imageButton}
                aria-label={`预览 ${label}`}
                onClick={() => setPreview(image)}
              >
                <img
                  className={styles.image}
                  src={image.dataUrl}
                  alt={label}
                  loading="lazy"
                  onError={() => setFailedKeys((prev) => ({ ...prev, [key]: true }))}
                />
              </button>
            );
          })}
        </div>
      )}
      <div className={styles.text}>
        {composed && command && <SlashCommandChip command={command} />}
        {display.referenceTitles.map((title, index) => <RecordReferenceTag key={`${title}-${index}`} title={title} />)}
        {(composed || display.referenceTitles.length > 0) ? display.prompt && <span>{display.prompt}</span> : content}
      </div>
      {preview && (
        <Modal
          open
          title={preview.name || '图片预览'}
          onClose={() => setPreview(null)}
          width={760}
          centered
        >
          <div className={styles.previewWrap}>
            <img className={styles.previewImage} src={preview.dataUrl} alt={preview.name || '图片预览'} />
            {preview.fileId && <div className={styles.previewMeta}>档案 ID：{preview.fileId}</div>}
          </div>
        </Modal>
      )}
    </div>
  );
}
