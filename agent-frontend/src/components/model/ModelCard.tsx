import React from 'react';
import { Trash2, Check, Loader2 } from 'lucide-react';
import { VendorIcon } from './VendorIcon';
import styles from './ModelCard.module.css';

export interface ModelCardItem {
  providerId: string;
  providerType: string;
  providerName?: string;
  id: string;
  name: string;
  baseUrl?: string;
  apiKey?: string;
  description?: string;
  supportsReasoning?: boolean;
  supportsVision?: boolean;
  contextWindow?: number;
}

interface ModelCardProps {
  item: ModelCardItem;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
  onTest: () => void;
  isTesting?: boolean;
  testResult?: { success: boolean; message: string };
}

const VENDOR_DISPLAY_NAMES: Record<string, string> = {
  gemini: 'Google Gemini',
  deepseek: 'DeepSeek',
  openai: 'OpenAI',
  dashscope: '通义千问',
  qwen: '通义千问',
  anthropic: 'Anthropic Claude',
  ollama: 'Ollama',
};

/** 将上下文窗口长度格式化为可读的 K/M 单位。 */
function formatContextWindow(n?: number): string | null {
  if (!n || n <= 0) return null;
  if (n >= 1_000_000) {
    return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
  }
  if (n >= 1_000) {
    return `${Math.round(n / 1_000)}K`;
  }
  return `${n}`;
}

export const ModelCard: React.FC<ModelCardProps> = ({
  item,
  isActive,
  onSelect,
  onDelete,
  onTest,
  isTesting = false,
  testResult,
}) => {
  const vendorKey = (item.providerType || item.providerId || '').toLowerCase();
  const vendorName = VENDOR_DISPLAY_NAMES[vendorKey] || item.providerName || vendorKey.toUpperCase();
  const contextLabel = formatContextWindow(item.contextWindow);

  return (
    <div className={`${styles.card} ${isActive ? styles.cardActive : ''}`}>
      <div className={styles.main}>
        <div className={styles.iconTile} aria-hidden="true">
          <VendorIcon vendor={vendorKey} size={22} />
        </div>

        <div className={styles.info}>
          <span className={styles.modelName} title={item.id}>{item.name}</span>

          <div className={styles.metaRow}>
            <span className={styles.vendorName}>{vendorName}</span>
            {item.supportsReasoning && (
              <span className={styles.reasoningTag}>思考</span>
            )}
            {item.supportsVision && (
              <span className={styles.visionTag}>图片识别</span>
            )}
            {contextLabel && (
              <span className={styles.contextTag}>{contextLabel} 上下文</span>
            )}
          </div>

          {item.description && (
            <span className={styles.desc}>{item.description}</span>
          )}
        </div>
      </div>

      <div className={styles.actions}>
        {testResult && (
          <span className={`${styles.testStatus} ${testResult.success ? styles.testOk : styles.testFail}`}>
            {testResult.success ? '连接正常' : '连接失败'}
          </span>
        )}

        <button
          className={styles.testBtn}
          onClick={onTest}
          disabled={isTesting}
        >
          {isTesting && <Loader2 size={13} className={styles.spin} />}
          {isTesting ? '测试中...' : '测试连接'}
        </button>

        {isActive ? (
          <span className={styles.activeTag}>
            <Check size={12} />
            当前模型
          </span>
        ) : (
          <button className={styles.selectBtn} onClick={onSelect}>
            设为当前
          </button>
        )}

        <button
          className={styles.deleteBtn}
          onClick={onDelete}
          title="删除模型"
          aria-label={`删除模型 ${item.name}`}
        >
          <Trash2 size={14} />
        </button>
      </div>
    </div>
  );
};
