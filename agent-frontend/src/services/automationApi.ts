import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';
import type { AutomationPreview, AutomationRun, AutomationSnapshot, AutomationSpec, AutomationTask, ComputerActivity, SaveTaskMailSettings, TaskMailSettings } from '../types/automation';

async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/automations${path}`, {
    method, headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(35000),
  });
  let payload: ApiResponse<T> | null = null;
  try { payload = await response.json() as ApiResponse<T>; } catch { /* 代理错误可能返回非 JSON 内容。 */ }
  if (!response.ok || !payload || payload.code !== 200) {
    if (!response.ok) {
      const status = response.status ? `（HTTP ${response.status}）` : '';
      throw new Error(`${payload?.message || '任务服务暂不可用'}${status}`);
    }
    throw new Error(payload?.message || '任务服务返回了无效响应');
  }
  return payload.data;
}
export const fetchAutomations = () => request<AutomationSnapshot>('');
export const saveAutomation = (spec: AutomationSpec, enabled: boolean, task?: AutomationTask) =>
  request<AutomationTask>(task ? `/${task.id}` : '', task ? 'PUT' : 'POST', { spec, enabled, version: task?.version ?? 0 });
export const setAutomationEnabled = (task: AutomationTask, enabled: boolean) => request<AutomationTask>(`/${task.id}/state`, 'POST', { enabled, version: task.version });
export const deleteAutomation = (task: AutomationTask) => request<void>(`/${task.id}?version=${task.version}`, 'DELETE');
export const restoreAutomation = (task: AutomationTask) => request<AutomationTask>(`/${task.id}/restore?version=${task.version}`, 'POST');
export const purgeDeletedAutomations = () => request<number>('/deleted/purge', 'DELETE');
export const previewAutomation = (spec: AutomationSpec) => request<AutomationPreview>('/preview', 'POST', spec);
export const runAutomation = (id: string) => request<AutomationRun>(`/${id}/run`, 'POST');
export const fetchAutomationHistory = (id: string) => request<AutomationRun[]>(`/${id}/runs`);
export const retryAutomationEmail = (id: string, allowUnknown: boolean) => request<void>(`/runs/${id}/retry-email`, 'POST', { allowUnknown });
export const confirmAutomation = (id: string) => request<void>(`/runs/${id}/confirm`, 'POST');
export const claimAutomation = (id: string) => request<boolean>(`/runs/${id}/claim`, 'POST');
export const automationReceipt = (id: string, status: string) => request<void>(`/runs/${id}/receipt`, 'POST', { status });
export const reportComputerActivity = (sample: ComputerActivity) => request<void>('/activity', 'POST', sample);
export const fetchTaskMailSettings = () => request<TaskMailSettings>('/mail/settings');
export const saveTaskMailSettings = (input: SaveTaskMailSettings) => request<TaskMailSettings>('/mail/settings', 'PUT', input);
export const testTaskMail = () => request<{ status: string; message: string }>('/mail/test', 'POST');
export { subscribeAutomations } from './automationRealtime';
