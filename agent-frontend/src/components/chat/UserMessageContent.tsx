import type { ChatImageAttachment } from '../../types/chat';
import { findSlashCommand, parseSlashCommand } from '../../features/slash-command/slashCommands';
import { parseSlashCommandDisplayArguments } from '../../features/slash-command/slashCommandDisplay';
import { RecordReferenceTag } from './RecordReferenceTag';
import { SlashCommandChip } from './SlashCommandChip';
import styles from './UserMessageContent.module.css';

/** 用户消息统一展示：资料引用、命令和图片可以并存，不因展示分支隐藏附件。 */
export function UserMessageContent({ content, images = [] }: { content: string; images?: ChatImageAttachment[] }) {
  const parsed = parseSlashCommand(content);
  const command = parsed ? findSlashCommand(parsed.name) : undefined;
  const composed = command?.presentation.selection === 'COMPOSE';
  const display = parseSlashCommandDisplayArguments(composed && parsed ? parsed.args : content);
  return (
    <div className={styles.userMessage}>
      {images.length > 0 && (
        <div className={styles.images}>
          {images.map((image, index) => (
            <img key={`${image.name || 'image'}-${index}`} className={styles.image}
              src={image.dataUrl} alt={image.name || `图片 ${index + 1}`} />
          ))}
        </div>
      )}
      <div className={styles.text}>
        {composed && command && <SlashCommandChip command={command} />}
        {display.referenceTitles.map((title, index) => <RecordReferenceTag key={`${title}-${index}`} title={title} />)}
        {(composed || display.referenceTitles.length > 0) ? display.prompt && <span>{display.prompt}</span> : content}
      </div>
    </div>
  );
}
