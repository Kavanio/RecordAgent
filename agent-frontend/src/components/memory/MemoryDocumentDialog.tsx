import { useEffect, useRef, useState } from 'react';
import { correctMemoryDocument, deleteMemoryDocument, fetchMemoryDocument, fetchMemoryOperation, retryMemoryOperation } from '../../services/experienceMemoryService';
import type { MemoryDocumentDetail, MemoryOperation } from '../../types/experienceMemory';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { Modal } from '../common/Modal';
import { useMessage } from '../common/Message';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import styles from './MemoryDocumentDialog.module.css';

const STATES: Record<MemoryOperation['state'], string> = {
  PENDING: '等待核对远端删除', WAITING_REMOTE: '等待在途保存结束', SUCCEEDED: '记忆来源撤回已完成',
  FAILED: '远端删除核对失败', UNKNOWN: '远端删除结果未知',
};
interface Props { id: string; onClose: () => void; onChanged: () => void }

/** 原文、独立纠正和删除任务详情；刷新恢复原任务，不自动发送远端请求。 */
export function MemoryDocumentDialog({ id, onClose, onChanged }: Props) {
  const { showMessage } = useMessage();
  const [detail, setDetail] = useState<MemoryDocumentDetail | null>(null);
  const [operation, setOperation] = useState<MemoryOperation | null>(null);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [confirm, setConfirm] = useState<'CORRECT' | 'DELETE' | null>(null);
  const command = useRef<{ key: string; id: string } | null>(null);
  const load = async () => {
    const next = await fetchMemoryDocument(id);
    setDetail(next); setDraft(next.correctedText ?? '');
    setOperation(next.document.operationId ? await fetchMemoryOperation(next.document.operationId) : null);
  };
  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const next = await fetchMemoryDocument(id);
        const task = next.document.operationId ? await fetchMemoryOperation(next.document.operationId) : null;
        if (active) { setDetail(next); setDraft(next.correctedText ?? ''); setOperation(task); }
      } catch (cause) { if (active) setError(cause instanceof Error ? cause.message : '来源详情读取失败'); }
      finally { if (active) setLoading(false); }
    })();
    return () => { active = false; };
  }, [id]);
  const refresh = async () => {
    setBusy(true); setError('');
    try { await load(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '详情刷新失败'); }
    finally { setBusy(false); }
  };
  const submit = async () => {
    if (!detail || !confirm) return;
    const key = JSON.stringify({ id, version: detail.document.version, kind: confirm, text: confirm === 'CORRECT' ? draft : '' });
    if (command.current?.key !== key) command.current = { key, id: crypto.randomUUID() };
    const input = { expectedVersion: detail.document.version, confirmed: true, requestId: command.current.id };
    setBusy(true);
    try {
      const task = confirm === 'CORRECT' ? await correctMemoryDocument(id, { ...input, correctedText: draft }) : await deleteMemoryDocument(id, input);
      setOperation(task); setConfirm(null);
      showMessage('success', task.state === 'SUCCEEDED' ? '来源已停用，无待核对的远端目标' : '来源已停用，远端删除仍须核对');
      await load(); onChanged();
    } catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '操作失败，请刷新核对状态'); }
    finally { setBusy(false); }
  };
  const retry = async () => {
    if (!operation) return;
    setBusy(true);
    try {
      const task = await retryMemoryOperation(operation.id);
      setOperation(task); onChanged();
      showMessage(task.state === 'SUCCEEDED' ? 'success' : 'info', STATES[task.state]);
    } catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '核对失败，请读取原任务状态'); }
    finally { setBusy(false); }
  };
  return <>
    <Modal open title="经历来源详情" width={680} busy={busy} onClose={onClose}>
      <div className={styles.content}>
        {loading && <p role="status">正在读取来源详情…</p>}
        {error && <p role="alert">{error}</p>}
        <div className={styles.actions}><Button variant="outline" disabled={busy || loading} onClick={() => void refresh()}>刷新详情</Button></div>
        {detail && <>
          <SettingsGroup title="来源原文" description="原聊天保持原样。缺失、损坏或超限的原文不会用其他内容替代。">
            <SettingsRow label={`版本 ${detail.document.version} · ${detail.document.state === 'REVOKED' ? '已停用' : '已登记'}`} description={id} control={<span>{detail.document.channel === 'DESKTOP' ? '桌面' : '飞书'}</span>} />
            <pre className={styles.original}>{detail.originalStatus === 'AVAILABLE' ? detail.originalText : '原文不可取得'}</pre>
          </SettingsGroup>
          <SettingsGroup title="纠正内容" description="确认后立即停用旧来源，纠正保存在当前应用后端，不提交外部记忆服务、不改写原聊天，也不冒充已生效的远端记忆。">
            <div className={styles.editor}><label htmlFor="memory-correction">你的纠正（最多 4000 字符）</label>
              <textarea id="memory-correction" value={draft} disabled={busy} maxLength={4000} onChange={(e) => setDraft(e.target.value)} />
              <div className={styles.actions}><Button variant="primary" disabled={busy || !draft.trim() || draft === detail.correctedText} onClick={() => setConfirm('CORRECT')}>保存纠正并停用旧来源</Button></div>
            </div>
          </SettingsGroup>
          {operation && <SettingsGroup title="纠正／删除任务">
            <SettingsRow label={STATES[operation.state]} description={operation.kind === 'CORRECT' ? '纠正保存在当前应用后端，不提交外部记忆服务；此任务核对旧来源的远端删除。' : '只撤回经历记忆，不删除原聊天或业务数据。'} control={<Button variant="outline" disabled={busy || !operation.retryAllowed} onClick={() => void retry()}>继续核对删除</Button>} />
          </SettingsGroup>}
          <SettingsGroup title="撤回此记忆来源">
            <SettingsRow label="删除记忆来源" description="删除本域纠正并停用来源；存在远端目标时，核对完成前保持未完成状态。原聊天保留。" control={<Button variant="danger" disabled={busy} onClick={() => setConfirm('DELETE')}>删除记忆来源</Button>} />
          </SettingsGroup>
        </>}
      </div>
    </Modal>
    <ConfirmDialog open={confirm !== null} title={confirm === 'CORRECT' ? '确认纠正并停用旧来源' : '确认删除记忆来源'} tone="danger" busy={busy} onClose={() => setConfirm(null)} onConfirm={submit} confirmText="确认操作"
      description={confirm === 'CORRECT' ? '纠正内容保存在当前应用后端，不提交外部记忆服务。旧来源立即停止同步和召回，远端旧数据的删除仍须核对。原聊天不变。' : '来源将停止同步和召回，本域纠正将被删除；原聊天保留。远端数据只有核对成功后才算完成删除。'} />
  </>;
}
