import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';
import type { TokenUsageOverview } from '../types/tokenUsage';

/** 查询 Token 用量总览；日期参数是包含首尾的本地自然日。 */
export async function fetchTokenUsageOverview(params?: {
  from?: string;
  to?: string;
  sessionId?: string;
}): Promise<TokenUsageOverview> {
  const search = new URLSearchParams();
  if (params?.from) search.set('from', params.from);
  if (params?.to) search.set('to', params.to);
  if (params?.sessionId) search.set('sessionId', params.sessionId);
  const suffix = search.size > 0 ? `?${search.toString()}` : '';
  const response = await apiFetch(`${getApiBaseUrl()}/agent/token-usage/overview${suffix}`);
  if (!response.ok) throw new Error(`读取 Token 用量失败：HTTP ${response.status}`);
  const json = await response.json() as ApiResponse<TokenUsageOverview>;
  if (json.code !== 200 || !json.data) throw new Error(json.message || '读取 Token 用量失败');
  return json.data;
}
