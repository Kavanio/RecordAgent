import { useEffect, useState } from 'react';
import { fetchMemoryDocuments } from '../../services/experienceMemoryService';
import type { MemoryDocument } from '../../types/experienceMemory';
import { Button } from '../common/Button';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { MemoryDocumentDialog } from './MemoryDocumentDialog';
import styles from './MemoryDocumentManager.module.css';

const SYNC_LABELS: Record<string, string> = {
  NOT_QUEUED: '已登记，尚未入队', PENDING: '等待同步', CLAIMED: '正在处理', SUBMITTED: '已提交，等待确认',
  SUCCEEDED: '远端写入已确认', FAILED: '同步失败', UNKNOWN: '远端结果未知', RETRY_WAIT: '等待重试', CANCELLED: '同步已停止',
};

/** 有界来源列表；不把入队状态当作已记住，详情按用户点击读取。 */
export function MemoryDocumentManager() {
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
  return <SettingsGroup title="经历来源管理" description="这里只显示已获准登记的来源，不导入旧历史。纠正或删除不会改写原聊天；删除结果须单独核对。">
    <SettingsRow label="来源记录" control={<Button variant="outline" disabled={busy} onClick={() => void load()}>刷新来源</Button>} />
    {busy && !items.length && <p className={styles.state} role="status">正在读取来源…</p>}
    {error && <p className={styles.state} role="alert">{error}</p>}
    {!busy && !error && !items.length && <p className={styles.state}>暂无经历来源。未接入或未授权时，普通聊天不会登记到这里。</p>}
    {items.map((item) => <SettingsRow key={item.id} label={`${item.channel === 'DESKTOP' ? '桌面' : '飞书'} · ${new Date(item.occurredAt).toLocaleString('zh-CN')}`}
      description={<span>{item.state === 'REVOKED' ? '来源已停用' : SYNC_LABELS[item.syncState] ?? '处理状态待核对'}{item.corrected ? ' · 纠正未提交外部记忆服务' : ''}{['PENDING', 'FAILED', 'UNKNOWN'].includes(item.deletionState) ? ' · 远端删除未完成' : item.deletionState === 'VERIFIED' ? ' · 远端删除已核对' : ''}</span>}
      control={<Button variant="ghost" aria-label={`查看来源 ${item.id}`} onClick={() => setSelected(item.id)}>查看来源</Button>} />)}
    {cursor && <div className={styles.more}><Button variant="outline" disabled={busy} onClick={() => void load(cursor)}>{busy ? '正在加载' : '加载更多来源'}</Button></div>}
    {selected && <MemoryDocumentDialog key={selected} id={selected} onClose={() => setSelected(null)} onChanged={() => void load()} />}
  </SettingsGroup>;
}
