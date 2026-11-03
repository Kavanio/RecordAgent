import { useCallback, useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, MessageSquare, RefreshCw } from 'lucide-react';
import { Drawer } from '../common/Drawer';
import { MarkdownContent } from '../common/MarkdownContent';
import { fetchFeishuConversation, fetchFeishuConversations } from '../../services/feishuConversationService';
import type { SessionDetailDto, SessionSummaryDto } from '../../types/chat';
import sidebar from './Sidebar.module.css';
import styles from './FeishuConversationSection.module.css';

/** 飞书会话独立只读分组，不进入桌面聊天状态、输入框或权限流程。 */
export function FeishuConversationSection({ query = '' }: { query?: string }) {
  const [expanded, setExpanded] = useState(true);
  const [sessions, setSessions] = useState<SessionSummaryDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<SessionSummaryDto | null>(null);
  const [detail, setDetail] = useState<SessionDetailDto | null>(null);
  const [detailError, setDetailError] = useState('');
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

  useEffect(() => {
    let cancelled = false;
    setDetail(null);
    setDetailError('');
    if (selected) fetchFeishuConversation(selected.id).then(value => {
      if (!cancelled) setDetail(value);
    }).catch(reason => {
      if (!cancelled) setDetailError(reason instanceof Error ? reason.message : '读取历史失败');
    });
    return () => { cancelled = true; };
  }, [selected, revision]);

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
          className={styles.session} onClick={() => setSelected(session)} title={session.title}>
          <MessageSquare size={14} /><span>{session.title}</span>
        </button>)}
      </div>}
      <Drawer open={selected !== null} title={selected?.title || '飞书对话'}
        description="飞书对话 · 只读历史，继续聊天请前往飞书" onClose={() => setSelected(null)} width={680}>
        <button type="button" className={styles.refresh} onClick={refresh}>刷新历史</button>
        {detailError ? <p role="alert">{detailError}<button type="button" onClick={refresh}>重试</button></p>
          : !detail ? <p role="status">正在读取历史…</p>
          : detail.messages.length === 0 ? <p>暂无消息</p>
          : <div className={styles.messages}>{detail.messages.map(message => <article key={message.id}
            className={styles.message} data-role={message.role}>
            <header>{message.role === 'USER' ? '我' : '助手'}
              <time dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleString('zh-CN')}</time>
              {message.status === 'FAILED' && <span>失败</span>}
              {message.status === 'CANCELLED' && <span>已停止</span>}
            </header>
            {message.role === 'USER' ? <p className={styles.original}>{message.content}</p>
              : <MarkdownContent content={message.content} />}
          </article>)}</div>}
      </Drawer>
    </section>
  );
}
