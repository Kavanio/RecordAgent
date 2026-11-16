import { apiEventSourceUrl, apiFetch, getApiBaseUrl, type ApiResponse } from './api';
import type {
  LifeSettings,
  LifeContent,
  LifeCandidate,
  LifeHabit,
  LifeHabitSuggestion,
  LifeNudge,
  LifeCompanionView,
  SaveLifeCognitionInput,
  EnableLifeCompanionInput,
} from '../types/lifeReview';

/** 不自动重试写入；读取不会触发模型或投递。 */
async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await apiFetch(`${getApiBaseUrl()}/agent/life-reviews${path}`, {
      method, headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(25_000),
    });
  } catch { throw new Error('生活复盘请求未完成，请刷新核对状态后再决定是否重试'); }
  let result: ApiResponse<T>;
  try { result = await response.json() as ApiResponse<T>; }
  catch { throw new Error('生活复盘响应不可读取，请检查后端版本'); }
  if (!response.ok || !result || typeof result !== 'object' || result.code !== 200 || !Object.hasOwn(result, 'data'))
    throw new Error(result?.message || '生活复盘服务不可用');
  return result.data;
}
const idPath = (id: string) => `/${encodeURIComponent(id)}`;
export const lifeReviewApi = {
  settings: () => request<LifeSettings>('/settings'),
  save: (s: LifeSettings & { imapPassword?: string }) => request<LifeSettings>('/settings', 'PUT', {
    expectedRevision: s.revision, enabled: s.enabled, sources: s.sources, providerId: s.providerId,
    modelName: s.modelName, timezone: s.timezone, historyDays: s.historyDays, dailyLimit: s.dailyLimit,
    proactive: s.proactive, feishu: s.feishu, idleCheckinDays: s.idleCheckinDays || 0,
    weatherAlertEnabled: Boolean(s.weatherAlertEnabled), expenseAlertEnabled: Boolean(s.expenseAlertEnabled),
    serverAlertEnabled: Boolean(s.serverAlertEnabled), serverProbeUrl: s.serverProbeUrl || '',
    serverProbeName: s.serverProbeName || '应用服务器',
    mailAlertEnabled: Boolean(s.mailAlertEnabled), imapHost: s.imapHost || '',
    imapPort: s.imapPort || 993, imapUsername: s.imapUsername || '',
    imapPassword: s.imapPassword || '', mailSenderWhitelist: s.mailSenderWhitelist || '',
    mailSubjectKeywords: s.mailSubjectKeywords || '',
  }),
  consent: (s: LifeSettings, grant: boolean) => request<LifeSettings>(grant ? '/grant' : '/revoke', 'POST', {
    expectedRevision: s.revision, policyVersion: s.policyVersion, confirmed: true,
  }),
  history: () => request<LifeContent[]>(''),
  archives: () => request<string[]>('/archives'),
  event: (topic: string, contentId: string, evidenceId: string, eventKind: 'EVENT' | 'RECOVERY') =>
    request<LifeCandidate[]>('/candidates', 'POST', { topic, contentId, evidenceId, eventKind }),
  candidates: () => request<LifeCandidate[]>('/candidates'),
  habit: () => request<LifeHabit | null>('/habit'),
  habitSuggestion: () => request<LifeHabitSuggestion>('/habit/suggestion'),
  saveHabit: (expectedRevision: number, availableHour: number, source: string, active: boolean) =>
    request<LifeHabit>('/habit', 'PUT', { expectedRevision, availableHour, source, active }),
  nudges: () => request<LifeNudge[]>('/nudges'),
  nudgeFeedback: (id: string, decision: 'HELPFUL' | 'NOT_NEEDED') =>
    request<LifeNudge[]>(`/nudges${idPath(id)}/feedback`, 'POST', { decision }),
  prepare: (day: string, expectedVersion = 0, supplement = false) =>
    request<LifeContent>('/prepare', 'POST', { day, expectedVersion, supplement }),
  send: (id: string, retry = false) => request<LifeContent>(`${idPath(id)}/${retry ? 'retry' : 'send'}`, 'POST', retry ? { confirmed: true } : undefined),
  reply: (id: string, requestId: string, text: string) => request<string>(`${idPath(id)}/reply`, 'POST', { requestId, text }),
  propose: (id: string) => request<LifeCandidate[]>(`${idPath(id)}/propose`, 'POST'),
  feedback: (id: string, decision: string) => request<LifeCandidate[]>(`/candidates${idPath(id)}/feedback`, 'POST', { decision, text: '' }),
  companion: () => request<LifeCompanionView>('/companion'),
  saveCognition: (input: SaveLifeCognitionInput) => request<LifeCompanionView>('/companion/cognition', 'PUT', input),
  enableCompanion: (input: EnableLifeCompanionInput) => request<LifeCompanionView>('/companion/enable', 'POST', input),
  learnCompanion: () => request<LifeCompanionView>('/companion/learn', 'POST'),
  reachOutCompanion: async (reasonHint?: string) => {
    const view = await request<LifeCompanionView>('/companion/reach-out', 'POST', { reasonHint: reasonHint || '' });
    if (typeof window !== 'undefined') {
      window.dispatchEvent(new CustomEvent('companion-outreach-updated', { detail: view }));
    }
    return view;
  },
  ackDesktopNotice: (id: string) => request<void>(`/companion/desktop-notices${idPath(id)}/ack`, 'POST'),
  subscribeCompanion: (onSnapshot: (value: LifeCompanionView) => void) => {
    let source: EventSource | null = null;
    let stopped = false;
    let failures = 0;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    const clear = () => {
      if (retryTimer !== undefined) clearTimeout(retryTimer);
      retryTimer = undefined;
      source?.close();
      source = null;
    };
    const connect = () => {
      if (stopped || typeof EventSource === 'undefined') return;
      clear();
      try {
        const current = new EventSource(apiEventSourceUrl(`${getApiBaseUrl()}/agent/life-reviews/companion/stream`));
        source = current;
        const onPayload = (event: MessageEvent<string>) => {
          if (stopped || source !== current) return;
          try {
            const view = JSON.parse(event.data) as LifeCompanionView;
            failures = 0;
            onSnapshot(view);
          } catch {
            // 忽略无法解析的损坏事件帧
          }
        };
        current.addEventListener('snapshot', onPayload as EventListener);
        current.addEventListener('updated', onPayload as EventListener);
        current.addEventListener('heartbeat', () => {
          if (!stopped && source === current) failures = 0;
        });
        current.onerror = () => {
          if (stopped || source !== current) return;
          clear();
          failures += 1;
          const delay = Math.min(30_000, 1000 * 2 ** Math.min(failures - 1, 5));
          retryTimer = setTimeout(connect, delay);
        };
      } catch {
        clear();
      }
    };
    const onOnline = () => connect();
    if (typeof window !== 'undefined') window.addEventListener('online', onOnline);
    connect();
    return {
      reconnect: connect,
      close() {
        stopped = true;
        clear();
        if (typeof window !== 'undefined') window.removeEventListener('online', onOnline);
      },
    };
  },
};
