import { useEffect, useRef, useState } from 'react';
import { correctMemoryDocument, deleteMemoryDocument, fetchMemoryDocument, fetchMemoryOperation, retryMemoryOperation } from '../../services/experienceMemoryService';
import type { MemoryDocumentDetail, MemoryOperation } from '../../types/experienceMemory';
import { cleanupProgressDescription, knownCleanupRecorded, memoryDeletionLabel } from '../../features/memory/cleanupPresentation';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { Modal } from '../common/Modal';
import { useMessage } from '../common/Message';
import styles from './MemoryDocumentDialog.module.css';

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
  const knownCleanup = operation?.state === 'UNKNOWN' && knownCleanupRecorded(operation.cleanupProgress);
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
      showMessage(task.state === 'SUCCEEDED' ? 'success' : 'info', memoryDeletionLabel(task.state, task.cleanupProgress));
    } catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '核对失败，请读取原任务状态'); }
    finally { setBusy(false); }
  };
  return <>
    <Modal open title="经历来源详情" width={480} busy={busy} onClose={onClose}>
      <div className={styles.content}>
        {loading && <p role="status">正在读取来源详情…</p>}
        {error && <p role="alert">{error}</p>}
        {detail && <>
          <div className={styles.meta}>
            <span>版本 {detail.document.version} · {detail.document.state === 'REVOKED' ? '已停用' : '已登记'} · {detail.document.channel === 'DESKTOP' ? '桌面' : '飞书'}</span>
            <Button variant="ghost" disabled={busy || loading} onClick={() => void refresh()}>刷新详情</Button>
          </div>
          <code className={styles.id}>{id}</code>
          <section className={styles.block}>
            <span className={styles.title}>来源原文</span>
            <p className={styles.hint}>原聊天不受影响；原文缺失或损坏时不拿其他内容替代。</p>
            <pre className={styles.original}>{detail.originalStatus === 'AVAILABLE' ? detail.originalText : '原文不可取得'}</pre>
          </section>
          <section className={styles.block}>
            <span className={styles.title}>纠正内容</span>
            <p className={styles.hint}>保存后旧来源立即停用。纠正只存在本机，不发外部记忆服务、不改原聊天。</p>
            <textarea id="memory-correction" aria-label="你的纠正" value={draft} disabled={busy} maxLength={4000} placeholder="最多 4000 字符" onChange={(e) => setDraft(e.target.value)} />
            <div className={styles.actions}><Button variant="primary" disabled={busy || !draft.trim() || draft === detail.correctedText} onClick={() => setConfirm('CORRECT')}>保存纠正并停用旧来源</Button></div>
          </section>
          {operation && <section className={styles.block}>
            <span className={styles.title}>纠正／删除任务</span>
            <div className={styles.actions}>
              <span className={styles.state}>{memoryDeletionLabel(operation.state, operation.cleanupProgress)}</span>
              <Button variant="outline" disabled={busy || (!knownCleanup && !operation.retryAllowed)} onClick={() => void (knownCleanup ? refresh() : retry())}>{knownCleanup ? '刷新清理进度' : '继续核对删除'}</Button>
            </div>
            {operation.cleanupProgress && <p className={styles.hint}>{cleanupProgressDescription(operation.cleanupProgress)}</p>}
            {knownCleanup && <p className={styles.hint}>现有可核对步骤已记录，刷新只读取进度，不重新删除。当前接口仍无法核实完整擦除。</p>}
            {operation.state === 'UNKNOWN' && <p className={styles.hint}>该来源保持停用；完整擦除未核实，本空间召回仍暂停。</p>}
            <p className={styles.hint}>{operation.kind === 'CORRECT' ? '只核对旧来源的远端删除，不重发纠正正文。' : '只撤回经历记忆，不删除原聊天或业务数据。'}</p>
          </section>}
          <section className={styles.block}>
            <span className={styles.title}>撤回此记忆来源</span>
            <p className={styles.hint}>停用来源并删除本域纠正；远端核对完成前不会显示为已删除。原聊天保留。</p>
            <div className={styles.actions}><Button variant="danger" disabled={busy} onClick={() => setConfirm('DELETE')}>删除记忆来源</Button></div>
          </section>
        </>}
      </div>
    </Modal>
    <ConfirmDialog open={confirm !== null} title={confirm === 'CORRECT' ? '确认纠正并停用旧来源' : '确认删除记忆来源'} tone="danger" busy={busy} onClose={() => setConfirm(null)} onConfirm={submit} confirmText="确认操作"
      description={confirm === 'CORRECT' ? '纠正内容保存在当前应用后端，不提交外部记忆服务。旧来源立即停止同步和召回，远端旧数据的删除仍须核对。原聊天不变。' : '来源将停止同步和召回，本域纠正将被删除；原聊天保留。远端数据只有核对成功后才算完成删除。'} />
  </>;
}
