import { useEffect, useState } from 'react';
import { getWritingPrivacyPreferences, subscribeWritingPrivacyPreferences } from '../../services/writingPrivacyPreferences';

export function useWritingPrivacyPreferences() {
  const [preferences, setPreferences] = useState(getWritingPrivacyPreferences);
  useEffect(() => {
    const refresh = () => setPreferences(getWritingPrivacyPreferences());
    const unsubscribe = subscribeWritingPrivacyPreferences(refresh);
    refresh();
    return unsubscribe;
  }, []);
  return preferences;
}
