import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Plus, ArrowUp, Mic, Square, X } from 'lucide-react';
import { ModelSelector } from '../model/ModelSelector';
import { PermissionModeSelector } from './PermissionModeSelector';
import type { ChatImageAttachment, SessionPermissionMode } from '../../types/chat';
import { useMessage } from '../common/Message';
import {
  createSpeechRecognition,
  isSpeechRecognitionSupported,
  requestMicrophoneAccess,
  type SpeechRecognitionController,
} from '../../services/speechRecognition';
import { CHAT_IMAGE_TYPES, MAX_CHAT_IMAGES } from '../../features/chat-images/chatImages';
import styles from './PromptInput.module.css';

interface PromptInputProps {
  value: string;
  onValueChange: (value: string) => void;
  onSend: () => void;
  onStop?: () => void;
  isStreaming?: boolean;
  isStopping?: boolean;
  onOpenSettings: () => void;
  className?: string;
  placeholder?: string;
  permissionMode: SessionPermissionMode;
  onPermissionModeChange: (mode: SessionPermissionMode) => void;
  isPermissionModeDisabled?: boolean;
  isPermissionModeSaving?: boolean;
  onInputKeyDown?: (event: React.KeyboardEvent<HTMLTextAreaElement>) => boolean;
  suggestionListId?: string;
  leadingContent?: React.ReactNode;
  canSend?: boolean;
  jevEnabled?: boolean;
  onJevEnabledChange?: (enabled: boolean) => void;
  isJevDisabled?: boolean;
  isJevSaving?: boolean;
  jevTitle?: string;
  /** 已附加的图片；由父组件持有，发送后清空。 */
  images?: ChatImageAttachment[];
  onAddImages?: (files: File[]) => Promise<void>;
  onRemoveImage?: (index: number) => void;
  imagesLoading?: boolean;
}

/**
 * AI 对话输入框：可组合前置标签、自动增高文本域与底部工具条。
 * 工具条左侧提供图片附件、审批模式与 Jev 开关，右侧为 AI 模型选择、语音听写与发送按钮。
 */
export const PromptInput: React.FC<PromptInputProps> = ({
  value,
  onValueChange,
  onSend,
  onStop,
  isStreaming = false,
  isStopping = false,
  onOpenSettings,
  className,
  placeholder = '随心输入',
  permissionMode,
  onPermissionModeChange,
  isPermissionModeDisabled = false,
  isPermissionModeSaving = false,
  onInputKeyDown,
  suggestionListId,
  leadingContent,
  canSend,
  jevEnabled = false,
  onJevEnabledChange,
  isJevDisabled = false,
  isJevSaving = false,
  jevTitle,
  images = [],
  onAddImages,
  onRemoveImage,
  imagesLoading = false,
}) => {
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const speechRecognitionRef = useRef<SpeechRecognitionController | null>(null);
  const dictationBasePromptRef = useRef('');
  const finalDictationRef = useRef('');
  const [isDictating, setIsDictating] = useState(false);
  const [isRequestingMicrophone, setIsRequestingMicrophone] = useState(false);
  const { showMessage } = useMessage();

  useEffect(() => () => speechRecognitionRef.current?.abort(), []);

  // 输入内容变化时按内容自动增高，最高不超过 200px。
  useLayoutEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 200)}px`;
  }, [value]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (onInputKeyDown?.(e)) return;
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      if (isStreaming || imagesLoading) return;
      onSend();
    }
  };

  const handleDictation = async () => {
    if (isDictating) {
      const recognition = speechRecognitionRef.current;
      speechRecognitionRef.current = null;
      setIsDictating(false);
      recognition?.stop();
      return;
    }
    if (!isSpeechRecognitionSupported()) {
      showMessage('error', '当前桌面环境不支持语音识别。');
      return;
    }

    setIsRequestingMicrophone(true);
    try {
      await requestMicrophoneAccess();
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '无法访问麦克风，请稍后重试。');
      return;
    } finally {
      setIsRequestingMicrophone(false);
    }

    dictationBasePromptRef.current = value;
    finalDictationRef.current = '';
    const recognition = createSpeechRecognition({
      onTranscript: (text, isFinal) => {
        if (isFinal) finalDictationRef.current += text;
        onValueChange(`${dictationBasePromptRef.current}${finalDictationRef.current}${isFinal ? '' : text}`);
      },
      onError: (message) => {
        // WebView 在识别失败时不一定派发 onend，错误发生后必须主动释放录音状态。
        speechRecognitionRef.current = null;
        setIsDictating(false);
        showMessage('error', message);
      },
      onEnd: () => {
        speechRecognitionRef.current = null;
        setIsDictating(false);
      },
    });
    if (!recognition) return;

    speechRecognitionRef.current = recognition;
    setIsDictating(true);
    try {
      recognition.start();
    } catch {
      speechRecognitionRef.current = null;
      setIsDictating(false);
      showMessage('error', '无法启动语音识别，请稍后重试。');
    }
  };

  const hasValue = value.trim().length > 0;
  const isSendEnabled = !imagesLoading && (canSend ?? (hasValue || images.length > 0));

  const handleFileSelect = (event: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = '';
    void onAddImages?.(files);
  };

  /** 支持直接粘贴截图；有图片时阻止默认粘贴，避免 Base64 文本污染输入框。 */
  const handlePaste = (event: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const files = Array.from(event.clipboardData?.files ?? []);
    if (files.length === 0) return;
    event.preventDefault();
    void onAddImages?.(files);
  };

  return (
    <div className={`${styles.container} ${className || ''}`}>
      {images.length > 0 && (
        <div className={styles.attachments} aria-label="已附加图片">
          {images.map((image, index) => (
            <div key={`${image.name || 'image'}-${index}`} className={styles.attachmentItem}>
              <img className={styles.attachmentImage} src={image.dataUrl} alt={image.name || '待发送图片'} />
              <button
                type="button"
                className={styles.attachmentRemove}
                onClick={() => onRemoveImage?.(index)}
                aria-label={`移除第 ${index + 1} 张图片`}
                title="移除"
              >
                <X size={12} />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className={styles.editor}>
        {leadingContent && <div className={styles.leadingContent}>{leadingContent}</div>}
        <textarea
          ref={textareaRef}
          rows={1}
          className={styles.textarea}
          placeholder={placeholder}
          value={value}
          onChange={(e) => onValueChange(e.target.value)}
          onKeyDown={handleKeyDown}
          onPaste={handlePaste}
          aria-expanded={Boolean(suggestionListId)}
          aria-controls={suggestionListId}
        />
      </div>

      <div className={styles.toolbar}>
        <div className={styles.toolbarLeft}>
          <input
            ref={fileInputRef}
            type="file"
            accept={CHAT_IMAGE_TYPES.join(',')}
            multiple
            hidden
            onChange={handleFileSelect}
          />
          <button
            type="button"
            className={styles.iconBtn}
            title="添加图片（也可直接粘贴）"
            aria-label="添加图片"
            disabled={!onAddImages || images.length >= MAX_CHAT_IMAGES}
            onClick={() => fileInputRef.current?.click()}
          >
            <Plus size={18} />
          </button>

          <div className={styles.divider} />

          <PermissionModeSelector
            value={permissionMode}
            onChange={onPermissionModeChange}
            disabled={isPermissionModeDisabled}
            isSaving={isPermissionModeSaving}
          />

          <button
            type="button"
            className={`${styles.jevButton} ${jevEnabled ? styles.jevButtonActive : ''}`}
            aria-pressed={jevEnabled}
            aria-busy={isJevSaving}
            aria-label={jevEnabled ? '关闭 Jev' : '开启 Jev'}
            disabled={isJevDisabled || isJevSaving || !onJevEnabledChange}
            title={jevTitle}
            onClick={() => onJevEnabledChange?.(!jevEnabled)}
          >
            Jev
          </button>
        </div>

        <div className={styles.toolbarRight}>
          <ModelSelector onOpenSettings={onOpenSettings} />

          <button
            type="button"
            className={`${styles.iconBtn} ${isDictating ? styles.micBtnActive : ''}`}
            title={isDictating ? '停止听写' : '开始语音听写'}
            aria-label={isDictating ? '停止听写' : '开始语音听写'}
            aria-pressed={isDictating}
            onClick={handleDictation}
            disabled={isRequestingMicrophone}
          >
            <Mic size={18} />
          </button>

          {isStreaming ? (
            <button
              type="button"
              className={styles.stopBtn}
              onClick={onStop}
              title={isStopping ? '正在停止…' : '停止当前回复'}
              aria-label={isStopping ? '正在停止当前回复' : '停止当前回复'}
              disabled={isStopping || !onStop}
            >
              <Square size={12} fill="currentColor" />
            </button>
          ) : (
            <button
              type="button"
              className={`${styles.sendBtn} ${isSendEnabled ? styles.sendBtnActive : ''}`}
              onClick={onSend}
              title="发送消息 (Enter)"
              aria-label="发送消息"
              disabled={!isSendEnabled}
            >
              <ArrowUp size={16} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default PromptInput;
