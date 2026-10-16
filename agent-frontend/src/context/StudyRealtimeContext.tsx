import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { finishStudySession } from '../services/studyApi';
import { subscribeStudyRealtime } from '../services/studyRealtime';
import { listenForStudyTrayFinish, syncStudyTray } from '../services/studyTray';
import type { StudySession } from '../types/study';
import { StudyRealtimeContext } from './studyRealtimeState';
import { useMessage } from '../components/common/Message';

/** 每个 WebView 只维护一个学习状态 SSE，并向所有学习视图提供一致快照。 */
export function StudyRealtimeProvider({ children }: { children: ReactNode }) {
  const { showMessage } = useMessage();
  const [activeSession, setActiveSession] = useState<StudySession | null>(null);
  const [ready, setReady] = useState(false);
  const [syncGeneration, setSyncGeneration] = useState(0);
  const [updatedAt, setUpdatedAt] = useState(0);
  const receivedInitialSnapshot = useRef(false);
  const activeSessionRef = useRef<StudySession | null>(null);
  const finishInFlight = useRef(false);

  useEffect(() => {
    activeSessionRef.current = activeSession;
  }, [activeSession]);

  useEffect(() => subscribeStudyRealtime((event) => {
    syncStudyTray(event);
    activeSessionRef.current = event.activeSession;
    setActiveSession(event.activeSession);
    setReady(true);
    setUpdatedAt(Date.now());
    if (receivedInitialSnapshot.current) setSyncGeneration((current) => current + 1);
    else receivedInitialSnapshot.current = true;
  }), []);

  useEffect(() => {
    let disposed = false;
    let unlisten: (() => void) | undefined;
    void listenForStudyTrayFinish(async () => {
      const session = activeSessionRef.current;
      if (!session || finishInFlight.current) return;
      finishInFlight.current = true;
      try {
        await finishStudySession(session.id, session.version);
        showMessage('success', '学习已结束，记录已保存');
      } catch (error) {
        showMessage('error', error instanceof Error ? error.message : '结束学习失败，请稍后重试');
      } finally {
        finishInFlight.current = false;
      }
    }).then((stop) => {
      if (disposed) stop();
      else unlisten = stop;
    }).catch((error: unknown) => {
      console.warn('注册菜单栏结束学习操作失败', error);
    });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [showMessage]);

  const value = useMemo(
    () => ({ activeSession, ready, syncGeneration, updatedAt }),
    [activeSession, ready, syncGeneration, updatedAt],
  );
  return <StudyRealtimeContext.Provider value={value}>{children}</StudyRealtimeContext.Provider>;
}
