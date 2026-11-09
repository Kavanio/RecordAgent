import { useEffect, useState, type ReactNode } from 'react';
import { ChatWorkspace } from './ChatWorkspace';
import { fetchFeishuConversation } from '../../services/feishuConversationService';
import { streamAgentChat } from '../../services/api';
import { mapTranscriptToChatMessage } from '../../features/chat/transcriptMessages';
import type {
  AgentAnalysisContextRequest,
  ChatImageAttachment,
  ChatMessage,
  SessionDetailDto,
  SessionSummaryDto,
} from '../../types/chat';
import styles from './ChannelConversationView.module.css';

const noop = () => {};

/** 同一主区域切换渠道；桌面视图留在原位以保留输入草稿，支持在桌面端直接与飞书主对话双向聊天。 */
export function ChannelConversationView({
  session,
  allowDesktopReply = false,
  children,
}: {
  session: SessionSummaryDto | null;
  allowDesktopReply?: boolean;
  children: ReactNode;
}) {
  return (
    <div className={styles.container}>
      <div className={styles.desktop} hidden={session !== null}>{children}</div>
      {session && (
        <FeishuWorkspace
          key={session.id}
          session={session}
          allowDesktopReply={allowDesktopReply}
        />
      )}
    </div>
  );
}

function FeishuWorkspace({
  session,
  allowDesktopReply,
}: {
  session: SessionSummaryDto;
  allowDesktopReply: boolean;
}) {
  const [detail, setDetail] = useState<SessionDetailDto | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [streaming, setStreaming] = useState(false);
  const [revision, setRevision] = useState(0);
  const refresh = () => setRevision(value => value + 1);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetchFeishuConversation(session.id)
      .then(value => {
        if (!cancelled) {
          setDetail(value);
          setMessages(value.messages.map(mapTranscriptToChatMessage));
        }
      })
      .catch(reason => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : '读取飞书历史失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [session.id, revision]);

  useEffect(() => {
    const onFocus = () => setRevision(value => value + 1);
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, []);

  const handleSendMessage = (
    prompt: string,
    modelContext?: string,
    recordReferenceIds?: string[],
    analysisContext?: AgentAnalysisContextRequest,
    images?: ChatImageAttachment[],
  ) => {
    if (!allowDesktopReply || streaming) return;
    const trimmed = prompt.trim();
    if (!trimmed && (!images || images.length === 0)) return;
    const now = Date.now();
    const userMsg: ChatMessage = {
      id: `feishu-user-${now}`,
      role: 'user',
      content: trimmed,
      createdAt: now,
      images,
    };
    const assistantId = `feishu-assistant-${now}`;
    const assistantMsg: ChatMessage = {
      id: assistantId,
      role: 'assistant',
      content: '',
      createdAt: now,
      startTime: now,
    };
    setMessages(prev => [...prev, userMsg, assistantMsg]);
    setStreaming(true);

    streamAgentChat(
      {
        runId: `feishu-run-${now}`,
        sessionId: session.id,
        content: trimmed,
        context: modelContext,
        recordReferenceIds,
        analysisContext,
        images: images?.map(image => ({
          name: image.name,
          mediaType: image.mediaType,
          dataBase64: image.dataUrl.split(',')[1] ?? '',
        })),
      },
      chunkText => {
        if (chunkText) {
          setMessages(prev =>
            prev.map(m => (m.id === assistantId ? { ...m, content: m.content + chunkText } : m)),
          );
        }
      },
      () => {
        setStreaming(false);
        refresh();
      },
      err => {
        setStreaming(false);
        setMessages(prev =>
          prev.map(m =>
            m.id === assistantId
              ? { ...m, status: 'FAILED', failureReason: err?.message || '发送失败' }
              : m,
          ),
        );
      },
    );
  };

  const banner = (
    <div className={styles.notice} role="status">
      <span>
        {allowDesktopReply
          ? '飞书与桌面互通主对话 · 桌面与飞书共享同一上下文，可直接在此回复'
          : '飞书对话 · 只读历史，继续聊天请前往飞书'}
      </span>
      <button type="button" onClick={refresh} disabled={loading || streaming}>
        刷新历史
      </button>
    </div>
  );

  return (
    <ChatWorkspace
      sessionId={session.id}
      sessionTitle={detail?.summary.title || session.title}
      messages={messages}
      sessionUsageSummary={detail?.usageSummary}
      isSessionLoading={loading}
      isSessionStreaming={streaming}
      sessionLoadError={error}
      onRetrySessionLoad={refresh}
      channelBanner={allowDesktopReply ? banner : undefined}
      readOnlyFooter={allowDesktopReply ? undefined : banner}
      onOpenFeature={noop}
      onOpenRecords={noop}
      onSendMessage={handleSendMessage}
      onOpenSettings={noop}
      onRenameSession={async () => ({ success: false, message: '请在飞书中管理会话' })}
      subagentTasks={[]}
      isSubagentTasksLoading={false}
      subagentTaskError={null}
      cancellingTaskId={null}
      onRefreshSubagentTasks={noop}
      onCancelSubagentTask={noop}
      permissionMode="ASK"
      onPermissionModeChange={noop}
      isPermissionModeDisabled
    />
  );
}
