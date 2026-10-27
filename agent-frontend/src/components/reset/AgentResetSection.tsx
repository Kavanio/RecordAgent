import { useState } from 'react';
import { Button } from '../common/Button';
import { TextInput } from '../common/TextInput';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { useMessage } from '../common/Message';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { getApiBaseUrl } from '../../services/api';
import { confirmAgentReset, fetchAgentReset, previewAgentReset } from '../../services/agentResetService';
import type { AgentResetPreview } from '../../types/agentReset';
import styles from './AgentResetSection.module.css';

/** 危险操作独立分组；先预览，再输入确认文字，超时后查询同一幂等任务。 */
export function AgentResetSection() {
  const { showMessage } = useMessage();
  const [preview, setPreview] = useState<AgentResetPreview | null>(null);
  const [backend, setBackend] = useState('');
  const [requestId, setRequestId] = useState('');
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [validation, setValidation] = useState('');
  async function open() {
    setBusy(true);
    try {
      const endpoint = getApiBaseUrl();
      const next = await previewAgentReset();
      setBackend(endpoint); setPreview(next); setRequestId(crypto.randomUUID()); setText(''); setValidation('');
    } catch (error) { showMessage('error', error instanceof Error ? error.message : '无法预览重置范围'); }
    finally { setBusy(false); }
  }
  async function confirm() {
    if (text !== '清除聊天与记忆') { setValidation('请输入完整文字：清除聊天与记忆'); return; }
    if (!preview) return;
    setBusy(true);
    try { await confirmAgentReset(preview, text, requestId, backend); setPreview(null); }
    catch (error) {
      showMessage('error', error instanceof Error ? error.message : '请求未确认，请查看重置状态');
      // 网络超时不代表后台未受理；先查询，重试仍复用原 requestId。
      await fetchAgentReset().catch(() => undefined);
    } finally { setBusy(false); }
  }
  return <>
    <SettingsGroup title="危险操作" description="清除 Agent 学到的个人上下文，保留业务记录。">
      <SettingsRow label="Agent 恢复出厂设置" description="先查看删除与保留范围，再明确二次确认。"
        control={<Button variant="danger" disabled={busy} onClick={() => void open()}>恢复出厂设置</Button>} />
    </SettingsGroup>
    <ConfirmDialog open={preview !== null} title="确认恢复 Agent 出厂设置" tone="danger" width={640}
      confirmText="确认清除聊天与记忆" busy={busy} onClose={() => setPreview(null)} onConfirm={confirm}>
      {preview && <div className={styles.scope}>
        <p>当前实际后端：<strong>{backend}</strong></p>
        <h3>将清除</h3><ul>{preview.deletes.map(item => <li key={item}>{item}</li>)}</ul>
        <h3>将保留</h3><ul>{preview.preserves.map(item => <li key={item}>{item}</li>)}</ul>
        <p>{preview.limitation}</p>
        <label htmlFor="reset-confirmation">输入“清除聊天与记忆”继续</label>
        <TextInput id="reset-confirmation" invalid={Boolean(validation)} value={text} onChange={event => setText(event.target.value)} autoComplete="off"
          aria-describedby={validation ? 'reset-validation' : undefined} />
        {validation && <p id="reset-validation" role="alert">{validation}</p>}
      </div>}
    </ConfirmDialog>
  </>;
}
