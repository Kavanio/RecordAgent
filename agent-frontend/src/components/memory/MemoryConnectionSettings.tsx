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
interface Draft { bankId: string; endpoint: string; token: string; clearToken: boolean; chain: string; accepted: boolean; plaintext: boolean }
const emptyDraft = (): Draft => ({ bankId: '', endpoint: '', token: '', clearToken: false, chain: '', accepted: false, plaintext: false });
function publicHttp(endpoint: string) {
  try { const url = new URL(endpoint); return url.protocol === 'http:' && !['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname); }
  catch { return false; }
}

/** 正式客户端的受控试用连接；连接确认、用途授权及保存／召回开关相互独立。 */
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
    setLoading(true); setError('');
    try { setConnection(await fetchMemoryTrialConnection()); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '记忆连接读取失败'); }
    finally { setLoading(false); }
  };
  useEffect(() => { void load(); }, []);
  const open = () => {
    setDraft({ ...emptyDraft(), endpoint: connection?.endpoint ?? '', bankId: connection?.bankId ?? '', chain: connection?.processingChain ?? '' });
    request.current = null; setFormError(''); setEditing(true);
  };
  const submit = async () => {
    if (!connection || saving) return;
    const endpoint = draft.endpoint.trim();
    const bankId = draft.bankId.trim();
    if (bankId && !/^[a-zA-Z0-9_-]{1,128}$/.test(bankId)) { setFormError('Bank ID 只允许 1—128 个英文字母、数字、下划线或连字符。'); return; }
    if (!endpoint || !draft.accepted) { setFormError('请填写服务地址，并确认真实聊天试用与删除限制。'); return; }
    if (publicHttp(endpoint) && !draft.plaintext) { setFormError('此地址使用公网明文 HTTP，请单独确认风险，或改用 HTTPS。'); return; }
    if (publicHttp(endpoint) && draft.token) { setFormError('公网明文试用不能携带 Token，请使用 HTTPS 或清空 Token。'); return; }
    const values = { bankId: connection.bankId === undefined ? undefined : bankId, expectedVersion: connection.version, endpoint, token: draft.token || null, clearToken: draft.clearToken,
      processingChain: draft.chain.trim(), acceptTrialRisks: draft.accepted, allowPlaintext: draft.plaintext };
    const key = JSON.stringify(values);
    if (request.current?.key !== key) request.current = { key, id: crypto.randomUUID() };
    const input: MemoryTrialConnect = { ...values, requestId: request.current.id };
    setSaving(true); setFormError('');
    try {
      setConnection(await connectMemoryTrial(input));
      request.current = null; setDraft(emptyDraft()); setEditing(false);
      await onChanged();
      showMessage('success', '试用连接已保存；请再确认用途授权，并开启需要的保存／召回开关');
    } catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '记忆连接保存失败，请刷新核对后重试'); }
    finally { setSaving(false); }
  };
  return <>
    <SettingsGroup title="Hindsight 连接 · 真实聊天试用" description="连接外部服务来保存和查找经历，使用可能产生费用。">
      <SettingsRow label="记忆服务" description={connection?.configured ? connection.endpoint : connection?.message ?? '正在读取连接配置…'}
        control={<div className={styles.actions}><Button variant="outline" disabled={loading || !connection || saving} onClick={open}>{connection?.configured ? '修改记忆服务' : '配置记忆服务'}</Button>
          <Button variant="ghost" disabled={loading || editing || saving} onClick={() => void load()}>刷新连接摘要</Button></div>} />
      <SettingsRow label="记忆库 Bank ID" description={connection?.bankId || '更新后端后可查看和配置记忆库'}
        control={<Button variant="outline" disabled={loading || saving || !connection?.bankId} onClick={open}>配置记忆库</Button>} />
      <SettingsRow label="试用限制" description="删除后会停止引用，但暂时无法保证远端彻底清除。" control={<span className={styles.badge}>删除未核实</span>} />
      <details className={styles.details}>
        <summary>查看连接详情和试用说明</summary>
        <p>{connection?.message}</p>
        <p>删除未核实时会暂停查找经历；恢复出厂也可能需要等待清理，不能当作已经清空。</p>
        <p>纠正内容目前只保存在本机，请勿用密码或敏感资料测试。</p>
      </details>
      {error && <SettingsRow label="连接读取失败" description={<span role="alert">{error}</span>} control={<Button variant="ghost" disabled={loading} onClick={() => void load()}>重新读取连接</Button>} />}
    </SettingsGroup>
    <ConfirmDialog open={editing} title="配置真实聊天记忆试用" width={600} busy={saving} confirmText="检查并保存连接" onClose={() => { setEditing(false); setDraft(emptyDraft()); request.current = null; }} onConfirm={submit}>
      <div className={styles.content}>
        <FormField label="Hindsight 服务地址" htmlFor="memory-service-endpoint" hint="填写 API 基础地址。由后端连接，不让前端直接访问 Hindsight。">
          <TextInput id="memory-service-endpoint" type="url" value={draft.endpoint} disabled={saving} maxLength={2048} placeholder="https://记忆服务地址/" onChange={(e) => setDraft(d => ({ ...d, endpoint: e.target.value }))} />
        </FormField>
        <FormField label="记忆库 Bank ID" htmlFor="memory-bank-id" hint="留空使用本应用固定的默认库。不会迁移或删除旧记忆；旧库存在未核实清理的远端目标时不能切换。请使用本应用专用库。">
          <TextInput id="memory-bank-id" value={draft.bankId} disabled={saving || connection?.bankId === undefined} maxLength={128} placeholder="留空使用默认库" onChange={(e) => setDraft(d => ({ ...d, bankId: e.target.value }))} />
        </FormField>
        <FormField label="接口 Token（没有则留空）" htmlFor="memory-service-token" hint={connection?.credentialConfigured ? '已有凭据不会回显；相同地址留空保留，地址变化不沿用旧凭据。' : '保存在后端受保护文件，不写浏览器缓存或聊天。公网 HTTP 不允许携带 Token。'}>
          <TextInput id="memory-service-token" type="password" autoComplete="new-password" value={draft.token} disabled={saving || draft.clearToken} maxLength={8192} onChange={(e) => setDraft(d => ({ ...d, token: e.target.value }))} />
        </FormField>
        {connection?.credentialConfigured && <label className={styles.option}><input type="checkbox" checked={draft.clearToken} disabled={saving} onChange={(e) => setDraft(d => ({ ...d, clearToken: e.target.checked, token: '' }))} />清除已保存的 Token</label>}
        <FormField label="模型／供应商说明（可留空）" htmlFor="memory-processing-chain" hint="绑定此服务当前的处理配置；服务换模型或供应商后需更新说明、重新授权。留空会标明处理链未自动核实。">
          <TextInput id="memory-processing-chain" value={draft.chain} disabled={saving} maxLength={400} onChange={(e) => setDraft(d => ({ ...d, chain: e.target.value }))} />
        </FormField>
        <label className={styles.option}><input aria-label="确认真实聊天试用风险" type="checkbox" checked={draft.accepted} disabled={saving} onChange={(e) => setDraft(d => ({ ...d, accepted: e.target.checked }))} />我确认此服务与当前处理配置，理解模型链未自动核实、远端彻底删除未保证；连接后还需单独授权，才可上传新聊天和查询。</label>
        {publicHttp(draft.endpoint) && <label className={styles.option}><input aria-label="允许本地址使用明文HTTP" type="checkbox" checked={draft.plaintext} disabled={saving} onChange={(e) => setDraft(d => ({ ...d, plaintext: e.target.checked }))} />我接受本地址使用未加密 HTTP，聊天可能被传输链路上的第三方看到；此确认不代表鉴权或访问控制已核实。</label>}
        {formError && <p role="alert">{formError}</p>}
      </div>
    </ConfirmDialog>
  </>;
}
