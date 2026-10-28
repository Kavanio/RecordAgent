import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';
import { setAgentGeneration } from './agentGeneration';
import type { AgentResetPreview, AgentResetStatus } from '../types/agentReset';

let latest: AgentResetStatus | null = null;
const listeners = new Set<(status: AgentResetStatus) => void>();
export const resetBlocked = (status: AgentResetStatus) => !['IDLE', 'LOCAL_COMPLETE'].includes(status.status);
export function subscribeAgentReset(listener: (status: AgentResetStatus) => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
async function request<T>(path: string, body?: unknown): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/reset${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`读取重置状态失败（HTTP ${response.status}）`);
  const result = await response.json() as ApiResponse<T>;
  if (result.code !== 200) throw new Error(result.message || '重置请求未完成');
  return result.data;
}
function publish(status: AgentResetStatus) {
  if (latest && (latest.generation > status.generation
    || (latest.generation === status.generation && ((latest.revision ?? 0) > (status.revision ?? 0)
      || (latest.status === 'LOCAL_COMPLETE' && status.status !== 'LOCAL_COMPLETE'))))) return latest;
  latest = status;
  setAgentGeneration(status.generation);
  listeners.forEach(listener => listener(status));
  return status;
}
export async function fetchAgentReset(): Promise<AgentResetStatus> {
  return publish(await request<AgentResetStatus>('/current'));
}
export function previewAgentReset(): Promise<AgentResetPreview> { return request('/preview', {}); }
export async function confirmAgentReset(preview: AgentResetPreview, confirmationText: string, requestId: string, backend: string) {
  if (backend !== getApiBaseUrl()) throw new Error('实际后端已变化，请重新预览');
  const status = await request<AgentResetStatus>('', { confirmationToken: preview.confirmationToken,
    scopeVersion: preview.scopeVersion, confirmationText, requestId });
  return publish(status);
}
export async function retryAgentReset(resetId: string) {
  return publish(await request<AgentResetStatus>(`/${encodeURIComponent(resetId)}/retry`, { confirmed: true }));
}
