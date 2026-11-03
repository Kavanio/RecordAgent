import { useCallback, useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, MessageSquare, RefreshCw } from 'lucide-react';
import { fetchFeishuConversations } from '../../services/feishuConversationService';
import type { SessionSummaryDto } from '../../types/chat';
import sidebar from './Sidebar.module.css';
import styles from './FeishuConversationSection.module.css';

/** 受控飞书分组；点击交给主区域展示，不持有弹层或正文。 */
export function FeishuConversationSection({ query = '', activeSessionId, onSelect }: {
  query?: string;
  activeSessionId?: string;
  onSelect?: (session: SessionSummaryDto) => void;
}) {
  const [expanded, setExpanded] = useState(true);
  const [sessions, setSessions] = useState<SessionSummaryDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision(value => value + 1), []);

  useEffect(() => {
    window.addEventListener('focus', refresh);
    return () => window.removeEventListener('focus', refresh);
  }, [refresh]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    fetchFeishuConversations().then(value => {
      if (!cancelled) setSessions(value);
    }).catch(reason => {
      if (!cancelled) setError(reason instanceof Error ? reason.message : '读取飞书对话失败');
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [revision]);

  const visible = sessions.filter(session => session.title.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <section className={sidebar.sectionGroup} aria-label="飞书对话">
      <div className={sidebar.groupHeader}>
        <button type="button" className={sidebar.groupTitleBtn} aria-expanded={expanded}
          onClick={() => setExpanded(value => !value)}>
          {expanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
          <span className={sidebar.groupTitle}>飞书对话</span>
          <span className={styles.count}>{sessions.length}</span>
        </button>
        <button type="button" className={sidebar.iconBtnSmall} aria-label="刷新飞书对话"
          title="刷新飞书对话" onClick={refresh} disabled={loading}><RefreshCw size={13} /></button>
      </div>
      {expanded && <div className={sidebar.sessionList}>
        {loading && <p className={sidebar.emptyText} role="status">正在读取飞书对话…</p>}
        {error && <div className={styles.feedback} role="alert">{error}
          <button type="button" onClick={refresh}>重试</button></div>}
        {!loading && !error && visible.length === 0 && <p className={sidebar.emptyText}>
          {query.trim() ? '没有匹配的飞书对话' : '暂无飞书对话'}</p>}
        {!loading && !error && visible.map(session => <button type="button" key={session.id}
          className={`${styles.session} ${session.id === activeSessionId ? styles.active : ''}`} aria-current={session.id === activeSessionId ? 'page' : undefined} onClick={() => onSelect?.(session)} title={session.title}>
          <MessageSquare size={14} /><span>{session.title}</span>
        </button>)}
      </div>}
    </section>
  );
}
