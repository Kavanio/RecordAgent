import React, { useState } from 'react';
import { Bot } from 'lucide-react';
import { saveModelConfig } from '../../services/api';
import { Select } from '../common/Select';
import { ModelEndpointFields } from './ModelEndpointFields';
import type { ModelProtocol } from '../../types/model';
import styles from './ModelInitPage.module.css';

interface ModelInitPageProps {
  vendors: string[];
  onSuccess: () => void;
}

const DEFAULT_MODEL_NAMES: Record<string, string> = {
  gemini: 'gemini-3.6-flash',
  openai: 'gpt-4o',
  deepseek: 'deepseek-chat',
  dashscope: 'qwen-max',
  anthropic: 'claude-3-5-sonnet-20241022',
  ollama: 'llama3:latest',
};

export const ModelInitPage: React.FC<ModelInitPageProps> = ({ vendors, onSuccess }) => {
  const [selectedVendor, setSelectedVendor] = useState<string>(() => vendors[0] || 'gemini');
  const [modelName, setModelName] = useState<string>(() => DEFAULT_MODEL_NAMES[vendors[0]] || 'gemini-3.6-flash');
  const [apiKey, setApiKey] = useState<string>('');
  const [baseUrl, setBaseUrl] = useState('');
  const [protocol, setProtocol] = useState<ModelProtocol>('openai');
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const handleVendorChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const v = e.target.value;
    setSelectedVendor(v);
    setBaseUrl('');
    if (v === 'custom') setModelName('');
    if (DEFAULT_MODEL_NAMES[v]) {
      setModelName(DEFAULT_MODEL_NAMES[v]);
    }
  };

  const handleCancel = () => {
    setApiKey('');
    setErrorMsg(null);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedVendor) {
      setErrorMsg('请选择模型厂商');
      return;
    }
    if (!modelName.trim()) {
      setErrorMsg('请填写模型名称');
      return;
    }
    if (selectedVendor !== 'ollama' && !apiKey.trim()) {
      setErrorMsg('请填写 API 密钥');
      return;
    }

    if (selectedVendor === 'custom' && !baseUrl.trim()) {
      setErrorMsg('请填写自定义 API 基础地址');
      return;
    }
    setIsSubmitting(true);
    setErrorMsg(null);

    const res = await saveModelConfig({
      vendor: selectedVendor,
      modelName: modelName.trim(),
      apiKey: apiKey.trim(),
      baseUrl: baseUrl.trim(),
      protocol: selectedVendor === 'custom' ? protocol : undefined,
    });

    setIsSubmitting(false);

    if (res.success) {
      onSuccess();
    } else {
      setErrorMsg(res.message || '保存配置失败，请检查网络');
    }
  };

  const isFormValid = selectedVendor === 'ollama' || apiKey.trim().length > 0;

  return (
    <div className={styles.container}>
      <div className={styles.titlebarDragRegion} data-tauri-drag-region aria-hidden="true" />
      <div className={styles.contentCard}>
        <div className={styles.header}>
          <Bot className={styles.logoIcon} strokeWidth={1.5} />
          <h1 className={styles.title}>配置 ButvanAgent</h1>
        </div>

        <form className={styles.form} onSubmit={handleSubmit}>
          {/* 模型厂商选择 */}
          <div className={styles.fieldGroup}>
            <Select
              label={<span className={styles.label}>模型厂商 Vendor</span>}
              options={[...new Set([...vendors, 'custom'])].map((vendor) => ({ value: vendor, label: vendor === 'custom' ? '自定义 API' : `${vendor.toUpperCase()} (${vendor})` }))}
              value={selectedVendor}
              onChange={handleVendorChange}
              fieldSize="lg"
              fullWidth
            />
          </div>

          <ModelEndpointFields idPrefix="init-model" vendor={selectedVendor} baseUrl={baseUrl} protocol={protocol}
            onBaseUrlChange={setBaseUrl} onProtocolChange={setProtocol} />

          {/* 模型名称 */}
          <div className={styles.fieldGroup}>
            <label className={styles.label}>模型名称 Model Name</label>
            <input
              className={styles.input}
              placeholder="如 gemini-3.6-flash"
              value={modelName}
              onChange={(e) => setModelName(e.target.value)}
            />
          </div>

          {/* API 密钥 */}
          {selectedVendor !== 'ollama' && (
            <div className={styles.fieldGroup}>
              <label className={styles.label}>API 密钥</label>
              <input
                className={styles.input}
                type="password"
                placeholder="sk-..."
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
              />
            </div>
          )}

          {errorMsg && <div className={styles.errorText}>{errorMsg}</div>}

          {/* 按钮组: 取消 / 继续 */}
          <div className={styles.buttonGroup}>
            <button
              type="button"
              className={styles.cancelBtn}
              onClick={handleCancel}
              disabled={isSubmitting}
            >
              取消
            </button>
            <button
              type="submit"
              className={`${styles.submitBtn} ${isFormValid ? styles.submitBtnActive : ''}`}
              disabled={isSubmitting}
            >
              {isSubmitting ? '保存中...' : '继续'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
