import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { subscribeStudyRealtime } from '../services/studyRealtime';
import { syncStudyTray } from '../services/studyTray';
import type { StudySession } from '../types/study';
import { StudyRealtimeContext } from './studyRealtimeState';

/** 每个 WebView 只维护一个学习状态 SSE，并向所有学习视图提供一致快照。 */
export function StudyRealtimeProvider({ children }: { children: ReactNode }) {
  const [activeSession, setActiveSession] = useState<StudySession | null>(null);
  const [ready, setReady] = useState(false);
  const [syncGeneration, setSyncGeneration] = useState(0);
  const [updatedAt, setUpdatedAt] = useState(0);
  const receivedInitialSnapshot = useRef(false);

  useEffect(() => subscribeStudyRealtime((event) => {
    syncStudyTray(event);
    setActiveSession(event.activeSession);
    setReady(true);
    setUpdatedAt(Date.now());
    if (receivedInitialSnapshot.current) setSyncGeneration((current) => current + 1);
    else receivedInitialSnapshot.current = true;
  }), []);

  const value = useMemo(
    () => ({ activeSession, ready, syncGeneration, updatedAt }),
    [activeSession, ready, syncGeneration, updatedAt],
  );
  return <StudyRealtimeContext.Provider value={value}>{children}</StudyRealtimeContext.Provider>;
}
