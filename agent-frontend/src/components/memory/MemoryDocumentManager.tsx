import { useEffect, useState } from 'react';
import { fetchMemoryDocuments } from '../../services/experienceMemoryService';
import { memoryDeletionLabel } from '../../features/memory/cleanupPresentation';
import type { MemoryDocument } from '../../types/experienceMemory';
import { Modal } from '../common/Modal';
import { Button } from '../common/Button';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { MemoryDocumentDialog } from './MemoryDocumentDialog';
import styles from './MemoryDocumentManager.module.css';

const SYNC_LABELS: Record<string, string> = {
  NOT_QUEUED: '已登记，尚未入队', PENDING: '等待同步', CLAIMED: '正在处理', SUBMITTED: '已提交，等待确认',
  SUCCEEDED: '远端写入已确认', FAILED: '同步失败', UNKNOWN: '远端结果未知', RETRY_WAIT: '等待重试', CANCELLED: '同步已停止',
};

/** 设置页仅保留管理入口，打开弹窗后才读取来源列表。 */
export function MemoryDocumentManager() {
  const [open, setOpen] = useState(false);
  return <>
    <SettingsGroup title="经历来源管理">
      <SettingsRow label="聊天来源" description="查看、纠正或删除记忆来源，原聊天会保留。"
        control={<Button variant="outline" onClick={() => setOpen(true)}>管理来源</Button>} />
    </SettingsGroup>
    {open && <Modal open title="经历来源管理" width={720} centered onClose={() => setOpen(false)}>
      <div className={styles.list}><MemorySourceList /></div>
    </Modal>}
  </>;
}

/** 有界来源列表；不把入队状态当作已记住，详情按用户点击读取。 */
function MemorySourceList() {
  const [items, setItems] = useState<MemoryDocument[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const load = async (next = '0') => {
    setBusy(true); setError('');
    try {
      const page = await fetchMemoryDocuments(next);
      setItems((current) => next === '0' ? page.items : [...current, ...page.items]);
      setCursor(page.nextCursor);
    } catch (cause) { setError(cause instanceof Error ? cause.message : '来源列表读取失败'); }
    finally { setBusy(false); }
  };
  useEffect(() => { void load(); }, []);
  return <SettingsGroup title="经历来源管理" description="只列出授权后登记的聊天，远端删除结果需逐条核对。">
    <SettingsRow label="来源记录" control={<Button variant="outline" disabled={busy} onClick={() => void load()}>刷新来源</Button>} />
    {busy && !items.length && <p className={styles.state} role="status">正在读取来源…</p>}
    {error && <p className={styles.state} role="alert">{error}</p>}
    {!busy && !error && !items.length && <p className={styles.state}>暂无经历来源，接入并授权后的新聊天才会出现在这里。</p>}
    {items.map((item) => <SettingsRow key={item.id} label={`${item.channel === 'DESKTOP' ? '桌面' : '飞书'} · ${new Date(item.occurredAt).toLocaleString('zh-CN')}`}
      description={<span>{item.state === 'REVOKED' ? '来源已停用' : SYNC_LABELS[item.syncState] ?? '处理状态待核对'}{item.corrected ? ' · 纠正未提交外部记忆服务' : ''}{memoryDeletionLabel(item.deletionState, item.cleanupProgress) ? ` · ${memoryDeletionLabel(item.deletionState, item.cleanupProgress)}` : ''}</span>}
      control={<Button variant="ghost" aria-label={`查看来源 ${item.id}`} onClick={() => setSelected(item.id)}>查看来源</Button>} />)}
    {cursor && <div className={styles.more}><Button variant="outline" disabled={busy} onClick={() => void load(cursor)}>{busy ? '正在加载' : '加载更多来源'}</Button></div>}
    {selected && <MemoryDocumentDialog key={selected} id={selected} onClose={() => setSelected(null)} onChanged={() => void load()} />}
  </SettingsGroup>;
}
