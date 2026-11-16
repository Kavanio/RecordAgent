import { useEffect, useRef, type Dispatch, type SetStateAction } from 'react';
import { lifeReviewApi } from '../../services/lifeReviewApi';
import { deliverTaskNotification } from '../../services/taskDesktop';
import { mapTranscriptMessages } from '../../features/chat/transcriptMessages';
import type { ChatSession, SessionDetailDto } from '../../types/chat';
import type { LifeCompanionView } from '../../types/lifeReview';

interface MessageOptions {
  duration?: number;
  action?: { label: string; onClick: () => void };
}

interface UseCompanionNotificationsOptions {
  showMessage: (type: 'info' | 'success' | 'error', content: string, options?: MessageOptions) => void;
  setIsSettingsOpen: (open: boolean) => void;
  setActiveFeature: (feature: 'chat') => void;
  setActiveSessionId: (id: string) => void;
  fetchSessionDetail: (id: string) => Promise<SessionDetailDto | null>;
  setSessions: Dispatch<SetStateAction<ChatSession[]>>;
}

/**
 * 订阅后端主动陪伴 SSE 实时流与即时问候事件，统一呈现系统提示音、应用内去回复提示并刷新桌面会话内容。
 */
export function useCompanionNotifications({
  showMessage,
  setIsSettingsOpen,
  setActiveFeature,
  setActiveSessionId,
  fetchSessionDetail,
  setSessions,
}: UseCompanionNotificationsOptions) {
  const ackedNoticesRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    let stopped = false;

    const processCompanionView = (companion: LifeCompanionView | null | undefined) => {
      if (stopped || !companion?.pendingDesktopNotices?.length) return;
      for (const notice of companion.pendingDesktopNotices) {
        if (notice.acknowledged || ackedNoticesRef.current.has(notice.id)) continue;
        ackedNoticesRef.current.add(notice.id);
        void lifeReviewApi.ackDesktopNotice(notice.id).catch(() => {});

        const ts = Date.parse(notice.createdAt) || Date.now();
        void deliverTaskNotification({
          id: notice.id,
          taskId: 'companion',
          title: notice.title || 'Agent 主动问候',
          content: notice.content,
          plannedAt: ts,
          createdAt: ts,
          source: 'COMPANION',
          status: 'RUNNING',
          desktopStatus: 'PENDING',
          emailStatus: 'SKIPPED',
          confirmation: 'NONE',
          sound: true,
          error: '',
        }).catch(() => {});

        showMessage('info', `💬 ${notice.title || 'Agent 主动问候'}：${notice.content.slice(0, 120)}`, {
          duration: 10000,
          action: {
            label: '去回复',
            onClick: () => {
              setIsSettingsOpen(false);
              setActiveFeature('chat');
              if (notice.sessionId) setActiveSessionId(notice.sessionId);
            },
          },
        });

        if (notice.sessionId) {
          void fetchSessionDetail(notice.sessionId).then((detail) => {
            if (!detail || stopped) return;
            const messages = mapTranscriptMessages(detail.messages);
            setSessions((previous) => previous.map((session) => (session.id === notice.sessionId
              ? {
                  ...session,
                  title: detail.summary.title,
                  lastMessagePreview: detail.summary.lastMessagePreview,
                  messages,
                  usageSummary: detail.usageSummary,
                  isLoaded: true,
                }
              : session)));
          }).catch(() => {});
        }
      }
    };

    const subscription = lifeReviewApi.subscribeCompanion(processCompanionView);
    const onOutreachUpdated = (event: Event) => {
      const custom = event as CustomEvent<LifeCompanionView>;
      if (custom.detail) processCompanionView(custom.detail);
    };
    window.addEventListener('companion-outreach-updated', onOutreachUpdated);
    return () => {
      stopped = true;
      subscription.close();
      window.removeEventListener('companion-outreach-updated', onOutreachUpdated);
    };
  }, [fetchSessionDetail, setActiveFeature, setActiveSessionId, setIsSettingsOpen, setSessions, showMessage]);
}
