import { useCallback, useState } from 'react';
import type { SessionSummaryDto } from '../../types/chat';

/** 保留桌面运行所绑定的会话；飞书选择只影响当前展示，不能覆盖桌面 run 与草稿。 */
export function useConversationSelection() {
  const [activeSessionId, setDesktopId] = useState('');
  const [feishuSession, selectFeishuSession] = useState<SessionSummaryDto | null>(null);
  const setActiveSessionId = useCallback((id: string) => {
    setDesktopId(id);
    selectFeishuSession(null);
  }, []);
  return { activeSessionId, setActiveSessionId, feishuSession, selectFeishuSession };
}
