import { useMemo, useState } from 'react';
import { ClockIcon, MagnifyingGlassIcon, MapPinIcon, PencilSimpleIcon, TrashIcon } from '@phosphor-icons/react';
import type { StudySession } from '../../types/study';
import { Drawer } from '../common/Drawer';
import { Select } from '../common/Select';
import { Button } from '../common/Button';
import styles from './StudyHistoryDrawer.module.css';

interface StudyHistoryDrawerProps {
  open: boolean;
  sessions: StudySession[];
  loading: boolean;
  saving: boolean;
  confirmDeleteId: string | null;
  onClose: () => void;
  onEdit: (session: StudySession) => void;
  onDelete: (session: StudySession) => void;
}

function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  if (minutes < 1) return seconds > 0 ? '不足 1 分钟' : '0 分钟';
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours ? `${hours} 小时${rest ? ` ${rest} 分钟` : ''}` : `${minutes} 分钟`;
}

function formatDate(instant: string): string {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'short' }).format(new Date(instant));
}

function formatClock(instant: string): string {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date(instant));
}

function sourceLabel(source: StudySession['source']): string {
  if (source === 'manual') return '手动补卡';
  if (source === 'shortcut') return '快捷记录';
  return '学习计时';
}

/** 学习记录抽屉：在不中断工作台阅读流的前提下筛选和维护近 30 天完整记录。 */
export function StudyHistoryDrawer({
  open, sessions, loading, saving, confirmDeleteId, onClose, onEdit, onDelete,
}: StudyHistoryDrawerProps) {
  const [keyword, setKeyword] = useState('');
  const [category, setCategory] = useState('all');
  const categories = useMemo(() => [...new Set(sessions.map((session) => session.category))], [sessions]);
  const filtered = useMemo(() => {
    const normalized = keyword.trim().toLocaleLowerCase('zh-CN');
    return sessions.filter((session) => (
      (category === 'all' || session.category === category)
      && (!normalized || `${session.content} ${session.location ?? ''}`.toLocaleLowerCase('zh-CN').includes(normalized))
    ));
  }, [category, keyword, sessions]);

  return <Drawer
    open={open}
    title="全部学习记录"
    description={loading ? '正在读取记录…' : `近 30 天共 ${sessions.length} 条，当前显示 ${filtered.length} 条`}
    onClose={onClose}
    width={600}
  >
    <div className={styles.filters} role="search" aria-label="筛选学习记录">
      <label className={styles.searchField}>
        <span>搜索记录</span>
        <div><MagnifyingGlassIcon size={14} aria-hidden="true" /><input type="search" value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="内容或地点" /></div>
      </label>
      <Select
        label="分类"
        value={category}
        onChange={(event) => setCategory(event.target.value)}
        fieldSize="sm"
        options={[
          { value: 'all', label: '全部分类' },
          ...categories.map((item) => ({ value: item, label: item })),
        ]}
      />
      {(keyword || category !== 'all') && (
        <Button variant="ghost" size="sm" onClick={() => { setKeyword(''); setCategory('all'); }}>
          清除筛选
        </Button>
      )}
    </div>

    <div className={styles.list} aria-live="polite">
      {loading ? <div className={styles.state}>正在读取学习记录…</div>
        : filtered.length ? filtered.map((session) => <article className={styles.row} key={session.id}>
          <time dateTime={session.startedAt}>{formatDate(session.startedAt)}</time>
          <div className={styles.rowHeading}><strong>{session.content}</strong><span>{session.category}</span></div>
          <div className={styles.meta}>
            <span><ClockIcon size={12} />{formatClock(session.startedAt)} — {session.endedAt ? formatClock(session.endedAt) : '进行中'}</span>
            <b>{formatDuration(session.durationSeconds)}</b>
            {session.location && <span><MapPinIcon size={12} />{session.location}</span>}
            <span>{sourceLabel(session.source)}</span>
          </div>
          <div className={styles.actions}>
            <button type="button" title="编辑" aria-label={`编辑“${session.content}”`} onClick={() => onEdit(session)}><PencilSimpleIcon size={14} /></button>
            <button type="button" disabled={saving} className={confirmDeleteId === session.id ? styles.confirmDelete : ''} title={confirmDeleteId === session.id ? '再次点击确认删除' : '删除'} aria-label={`删除“${session.content}”`} onClick={() => onDelete(session)}>{confirmDeleteId === session.id ? '确认删除' : <TrashIcon size={14} />}</button>
          </div>
        </article>) : <div className={styles.state}><strong>没有匹配的记录</strong><p>换个关键词或分类再试试。</p></div>}
    </div>
  </Drawer>;
}
