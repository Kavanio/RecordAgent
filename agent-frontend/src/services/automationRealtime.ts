import { apiEventSourceUrl, getApiBaseUrl } from './api';
import type { AutomationSnapshot, AutomationSyncStatus } from '../types/automation';

const SILENCE_TIMEOUT_MS = 45_000;
const RESUME_STALE_MS = 30_000;

/** 每窗口一个订阅；主动关闭旧连接后退避重连，恢复只以完整快照为准，不做 HTTP 轮询。 */
export function subscribeAutomations(
  onSnapshot: (value: AutomationSnapshot) => void,
  onState: (value: AutomationSyncStatus) => void,
): { close: () => void; reconnect: () => void } {
  let source: EventSource | null = null;
  let stopped = false;
  let failures = 0;
  let live = false;
  let lastFrameAt = Date.now();
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  let silenceTimer: ReturnType<typeof setTimeout> | undefined;
  const online = () => navigator.onLine !== false;
  function clearConnection() {
    clearTimeout(retryTimer); clearTimeout(silenceTimer);
    retryTimer = undefined; silenceTimer = undefined;
    const previous = source;
    source = null;
    previous?.close();
  }
  function failed() {
    if (stopped) return;
    clearConnection(); live = false; failures++;
    onState(!online() || failures >= 3 ? 'OFFLINE' : 'RECONNECTING');
    if (!online()) return;
    const delay = Math.min(30_000, 1000 * 2 ** Math.min(failures - 1, 5));
    retryTimer = setTimeout(connect, Math.min(30_000, delay * (0.8 + Math.random() * 0.4)));
  }
  function received() {
    lastFrameAt = Date.now();
    clearTimeout(silenceTimer);
    silenceTimer = setTimeout(failed, SILENCE_TIMEOUT_MS);
  }
  function connect() {
    if (stopped) return;
    clearConnection(); live = false;
    if (!online()) { onState('OFFLINE'); return; }
    onState(failures ? 'RECONNECTING' : 'CONNECTING');
    try {
      const current = new EventSource(apiEventSourceUrl(`${getApiBaseUrl()}/agent/automations/stream`));
      source = current;
      received();
      current.addEventListener('snapshot', event => {
        if (stopped || source !== current) return;
        let value: AutomationSnapshot;
        try {
          value = JSON.parse(event.data) as AutomationSnapshot;
          if (!value || !Array.isArray(value.tasks) || !Array.isArray(value.pending)) throw new Error('无效任务快照');
        } catch { failed(); return; }
        received(); failures = 0; live = true;
        onSnapshot(value);
        onState('LIVE');
      });
      current.addEventListener('heartbeat', () => {
        if (!stopped && source === current) received();
      });
      current.onerror = () => { if (!stopped && source === current) failed(); };
    } catch { failed(); }
  }
  function resume() {
    if (!live || Date.now() - lastFrameAt >= RESUME_STALE_MS) connect();
  }
  function visible() { if (document.visibilityState === 'visible') resume(); }
  function offline() { if (!stopped) { clearConnection(); live = false; onState('OFFLINE'); } }
  window.addEventListener('online', resume);
  window.addEventListener('offline', offline);
  window.addEventListener('focus', resume);
  document.addEventListener('visibilitychange', visible);
  connect();
  return {
    reconnect: connect,
    close() {
      stopped = true; clearConnection();
      window.removeEventListener('online', resume);
      window.removeEventListener('offline', offline);
      window.removeEventListener('focus', resume);
      document.removeEventListener('visibilitychange', visible);
    },
  };
}
