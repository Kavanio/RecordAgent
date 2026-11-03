import { useEffect, useState, type ReactNode } from 'react';
import { ChatWorkspace } from './ChatWorkspace';
import { fetchFeishuConversation } from '../../services/feishuConversationService';
import { mapTranscriptToChatMessage } from '../../features/chat/transcriptMessages';
import type { SessionDetailDto, SessionSummaryDto } from '../../types/chat';
import styles from './ChannelConversationView.module.css';

const noop = () => {};

/** 同一主区域切换渠道；桌面视图留在原位以保留输入草稿，飞书只读且无写入回调。 */
export function ChannelConversationView({ session, children }: {
  session: SessionSummaryDto | null;
  children: ReactNode;
}) {
  return <div className={styles.container}>
    <div className={styles.desktop} hidden={session !== null}>{children}</div>
    {session && <FeishuWorkspace key={session.id} session={session} />}
  </div>;
}

function FeishuWorkspace({ session }: { session: SessionSummaryDto }) {
  const [detail, setDetail] = useState<SessionDetailDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision(value => value + 1);
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchFeishuConversation(session.id).then(value => {
      if (!cancelled) setDetail(value);
    }).catch(reason => {
      if (!cancelled) setError(reason instanceof Error ? reason.message : '读取飞书历史失败');
    }).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [session.id, revision]);
  useEffect(() => {
    const onFocus = () => setRevision(value => value + 1);
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, []);
  return <ChatWorkspace
    sessionId={session.id} sessionTitle={detail?.summary.title || session.title}
    messages={detail?.messages.map(mapTranscriptToChatMessage) || []}
    sessionUsageSummary={detail?.usageSummary} isSessionLoading={loading}
    sessionLoadError={error} onRetrySessionLoad={refresh}
    readOnlyFooter={<div className={styles.notice} role="status">
      <span>飞书对话 · 只读历史，继续聊天请前往飞书</span>
      <button type="button" onClick={refresh} disabled={loading}>刷新历史</button>
    </div>}
    onOpenFeature={noop} onOpenRecords={noop} onSendMessage={noop} onOpenSettings={noop}
    onRenameSession={async () => ({ success: false, message: '请在飞书中管理会话' })}
    subagentTasks={[]} isSubagentTasksLoading={false} subagentTaskError={null}
    cancellingTaskId={null} onRefreshSubagentTasks={noop} onCancelSubagentTask={noop}
    permissionMode="ASK" onPermissionModeChange={noop} isPermissionModeDisabled
  />;
}
