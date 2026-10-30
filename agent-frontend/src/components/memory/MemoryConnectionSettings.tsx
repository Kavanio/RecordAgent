import { useEffect, useRef, useState } from 'react';
import { connectMemoryTrial, fetchMemoryTrialConnection } from '../../services/experienceMemoryService';
import type { MemoryTrialConnection, MemoryTrialConnect } from '../../types/experienceMemory';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import { useMessage } from '../common/Message';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import styles from './MemoryConnectionSettings.module.css';

interface Props { onChanged: () => Promise<void> }
interface Draft { bankId: string; endpoint: string; token: string; clearToken: boolean; plaintext: boolean }
const emptyDraft = (): Draft => ({ bankId: '', endpoint: '', token: '', clearToken: false, plaintext: false });
function publicHttp(endpoint: string) {
  try {
    const url = new URL(endpoint);
    return url.protocol === 'http:' && !['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname);
  } catch {
    return false;
  }
}

/** 记忆服务连接配置：首页只展示一行连接与记忆库摘要，点击按需弹窗配置。 */
export function MemoryConnectionSettings({ onChanged }: Props) {
  const { showMessage } = useMessage();
  const [connection, setConnection] = useState<MemoryTrialConnection | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [formError, setFormError] = useState('');
  const request = useRef<{ key: string; id: string } | null>(null);

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      setConnection(await fetchMemoryTrialConnection());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '记忆连接读取失败');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, []);

  const open = () => {
    setDraft({
      ...emptyDraft(),
      endpoint: connection?.endpoint ?? '',
      bankId: connection?.bankId ?? '',
    });
    request.current = null;
    setFormError('');
    setEditing(true);
  };

  const submit = async () => {
    if (!connection || saving) return;
    const endpoint = draft.endpoint.trim();
    const bankId = draft.bankId.trim();
    if (bankId && !/^[a-zA-Z0-9_-]{1,128}$/.test(bankId)) {
      setFormError('Bank ID 只允许 1—128 个英文字母、数字、下划线或连字符。');
      return;
    }
    if (!endpoint) {
      setFormError('请填写服务地址。');
      return;
    }
    if (publicHttp(endpoint) && !draft.plaintext) {
      setFormError('此地址使用公网明文 HTTP，请确认明文风险或改用 HTTPS。');
      return;
    }
    if (publicHttp(endpoint) && draft.token) {
      setFormError('公网明文连接不能携带 Token，请使用 HTTPS 或清空 Token。');
      return;
    }
    const values = {
      bankId: connection.bankId === undefined ? undefined : bankId,
      expectedVersion: connection.version,
      endpoint,
      token: draft.token || null,
      clearToken: draft.clearToken,
      processingChain: connection.processingChain ?? '',
      acceptTrialRisks: true,
      allowPlaintext: draft.plaintext,
    };
    const key = JSON.stringify(values);
    if (request.current?.key !== key) request.current = { key, id: crypto.randomUUID() };
    const input: MemoryTrialConnect = { ...values, requestId: request.current.id };
    setSaving(true);
    setFormError('');
    try {
      setConnection(await connectMemoryTrial(input));
      request.current = null;
      setDraft(emptyDraft());
      setEditing(false);
      await onChanged();
      showMessage('success', '记忆服务连接已保存');
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '记忆连接保存失败，请核对后重试');
    } finally {
      setSaving(false);
    }
  };

  const summary = connection?.configured
    ? `${connection.endpoint}${connection.bankId ? ` · 记忆库 ${connection.bankId}` : ''}`
    : (connection?.message ?? '正在读取连接配置…');

  return <>
    <SettingsGroup title="记忆服务连接" description="连接 Hindsight 服务以保存和查找经历。">
      <SettingsRow
        label="记忆服务"
        description={summary}
        control={
          <div className={styles.actions}>
            <Button variant="outline" disabled={loading || !connection || saving} onClick={open}>
              {connection?.configured ? '修改记忆服务' : '配置记忆服务'}
            </Button>
          </div>
        }
      />
      {error && (
        <SettingsRow
          label="连接读取失败"
          description={<span role="alert">{error}</span>}
          control={<Button variant="ghost" disabled={loading} onClick={() => void load()}>重新读取连接</Button>}
        />
      )}
    </SettingsGroup>
    <ConfirmDialog
      open={editing}
      title="配置记忆服务"
      width={540}
      busy={saving}
      confirmText="检查并保存连接"
      onClose={() => { setEditing(false); setDraft(emptyDraft()); request.current = null; }}
      onConfirm={submit}
    >
      <div className={styles.content}>
        <FormField label="Hindsight 服务地址" htmlFor="memory-service-endpoint" hint="填写 API 基础地址，由后端统一连接。">
          <TextInput
            id="memory-service-endpoint"
            type="url"
            value={draft.endpoint}
            disabled={saving}
            maxLength={2048}
            placeholder="https://记忆服务地址/"
            onChange={(e) => setDraft(d => ({ ...d, endpoint: e.target.value }))}
          />
        </FormField>
        <FormField label="记忆库 Bank ID（可留空）" htmlFor="memory-bank-id" hint="留空使用本应用默认库，也可填写自定义库 ID。">
          <TextInput
            id="memory-bank-id"
            value={draft.bankId}
            disabled={saving || connection?.bankId === undefined}
            maxLength={128}
            placeholder="留空使用默认库"
            onChange={(e) => setDraft(d => ({ ...d, bankId: e.target.value }))}
          />
        </FormField>
        <FormField
          label="接口 Token（没有则留空）"
          htmlFor="memory-service-token"
          hint={connection?.credentialConfigured ? '已保存凭据不会回显；相同地址留空默认保留。' : '保存在后端受保护文件中。'}
        >
          <TextInput
            id="memory-service-token"
            type="password"
            autoComplete="new-password"
            value={draft.token}
            disabled={saving || draft.clearToken}
            maxLength={8192}
            onChange={(e) => setDraft(d => ({ ...d, token: e.target.value }))}
          />
        </FormField>
        {connection?.credentialConfigured && (
          <label className={styles.option}>
            <input
              type="checkbox"
              checked={draft.clearToken}
              disabled={saving}
              onChange={(e) => setDraft(d => ({ ...d, clearToken: e.target.checked, token: '' }))}
            />
            清除已保存的 Token
          </label>
        )}
        {publicHttp(draft.endpoint) && (
          <label className={styles.option}>
            <input
              aria-label="允许本地址使用明文HTTP"
              type="checkbox"
              checked={draft.plaintext}
              disabled={saving}
              onChange={(e) => setDraft(d => ({ ...d, plaintext: e.target.checked }))}
            />
            我理解此公网地址使用未加密 HTTP（不能携带 Token）。
          </label>
        )}
        {formError && <p role="alert">{formError}</p>}
      </div>
    </ConfirmDialog>
  </>;
}
