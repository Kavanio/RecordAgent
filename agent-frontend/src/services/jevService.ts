import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';

export interface JevStatus {
  enabled: boolean;
  available: boolean;
}

async function request(path = '', init?: RequestInit): Promise<JevStatus> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/jev${path}`, {
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
    ...init,
  });
  const json = await response.json() as ApiResponse<JevStatus>;
  if (!response.ok || json.code !== 200 || !json.data) {
    throw new Error(json.message || 'Jev 状态更新失败');
  }
  return json.data;
}

/** 读取当前 Jev 总开关及配置可用性。 */
export const fetchJevStatus = () => request();

/** 更新 Jev 总开关，后端会保留其余本地配置。 */
export const updateJevEnabled = (enabled: boolean) => request('/enabled', {
  method: 'PUT',
  body: JSON.stringify({ enabled }),
});
