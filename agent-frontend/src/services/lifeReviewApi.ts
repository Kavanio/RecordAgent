import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';
import type { LifeSettings, LifeContent, LifeCandidate, LifeHabit } from '../types/lifeReview';

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
  save: (s: LifeSettings) => request<LifeSettings>('/settings', 'PUT', {
    expectedRevision: s.revision, enabled: s.enabled, sources: s.sources, providerId: s.providerId,
    modelName: s.modelName, timezone: s.timezone, historyDays: s.historyDays, dailyLimit: s.dailyLimit,
    proactive: s.proactive, feishu: s.feishu,
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
  saveHabit: (expectedRevision: number, availableHour: number, source: string, active: boolean) =>
    request<LifeHabit>('/habit', 'PUT', { expectedRevision, availableHour, source, active }),
  prepare: (day: string, expectedVersion = 0, supplement = false) =>
    request<LifeContent>('/prepare', 'POST', { day, expectedVersion, supplement }),
  send: (id: string, retry = false) => request<LifeContent>(`${idPath(id)}/${retry ? 'retry' : 'send'}`, 'POST', retry ? { confirmed: true } : undefined),
  reply: (id: string, requestId: string, text: string) => request<string>(`${idPath(id)}/reply`, 'POST', { requestId, text }),
  propose: (id: string) => request<LifeCandidate[]>(`${idPath(id)}/propose`, 'POST'),
  feedback: (id: string, decision: string) => request<LifeCandidate[]>(`/candidates${idPath(id)}/feedback`, 'POST', { decision, text: '' }),
};
