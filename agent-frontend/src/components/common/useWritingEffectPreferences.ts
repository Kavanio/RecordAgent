import { useEffect, useState } from 'react';
import { getWritingEffectPreferences, subscribeWritingEffectPreferences } from '../../services/writingEffectPreferences';

/** 让资料设置与已打开的写作编辑器共享已持久化的特效配置。 */
export function useWritingEffectPreferences() {
  const [preferences, setPreferences] = useState(getWritingEffectPreferences);
  useEffect(() => {
    const refresh = () => setPreferences(getWritingEffectPreferences());
    const unsubscribe = subscribeWritingEffectPreferences(refresh);
    refresh();
    return unsubscribe;
  }, []);
  return preferences;
}
