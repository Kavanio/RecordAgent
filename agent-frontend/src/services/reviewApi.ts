import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';
import type {
  ReviewEraseResult, ReviewFactSnapshot, ReviewFactSnapshotSummary, ReviewGoal, ReviewGrant, ReviewSettings,
  SaveReviewGoal, SaveReviewSettings,
} from '../types/review';

/** 仅由显式交互调用，读取设置不会启动采集／模型，也不自动重试写请求。 */
async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  let response: Response;
  try {
    response = await apiFetch(`${getApiBaseUrl()}/agent/reviews${path}`, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(35_000),
    });
  } catch (error) {
    if (error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError')) {
      throw new Error('长期复盘请求超时，请检查状态后再重试');
    }
    throw new Error('无法连接长期复盘服务，请检查后端连接');
  }
  let payload: ApiResponse<T>;
  try { payload = await response.json() as ApiResponse<T>; }
  catch { throw new Error('长期复盘服务返回了无效响应'); }
  if (!response.ok || !payload || payload.code !== 200 || !Object.hasOwn(payload, 'data')) {
    throw new Error(payload?.message || '长期复盘服务暂不可用');
  }
  return payload.data;
}

export const fetchReviewSettings = () => request<ReviewSettings>('/settings');
export const saveReviewSettings = (input: SaveReviewSettings) => request<ReviewSettings>('/settings', 'PUT', input);
export const grantReviewConsent = (expectedSettingsRevision: number, policyVersion: string, confirmed: boolean) =>
  request<ReviewGrant>('/grants', 'POST', { expectedSettingsRevision, policyVersion, confirmed });
export const revokeReviewConsent = (id: string, expectedRevision: number) =>
  request<ReviewGrant>(`/grants/${encodeURIComponent(id)}/revoke`, 'POST', { expectedRevision });
export const captureReviewFacts = (from: string, to: string, timezone: string) =>
  request<ReviewFactSnapshot>('/facts/capture', 'POST', { from, to, timezone });
export const fetchReviewFacts = (limit = 20) => request<ReviewFactSnapshotSummary[]>(`/facts?limit=${limit}`);
export const fetchReviewFact = (id: string) => request<ReviewFactSnapshot>(`/facts/${encodeURIComponent(id)}`);
export const fetchReviewGoals = () => request<ReviewGoal[]>('/goals');
export const saveReviewGoal = (input: SaveReviewGoal, id?: string) =>
  request<ReviewGoal>(id ? `/goals/${encodeURIComponent(id)}` : '/goals', id ? 'PUT' : 'POST', input);
export const eraseReviewData = (expectedSettingsRevision: number, confirmed: boolean) =>
  request<ReviewEraseResult>('/erase', 'POST', { expectedSettingsRevision, confirmed });
