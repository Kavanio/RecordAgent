import type { PersonalContextSettings, ProfileMaintenanceStatus } from '../types/personalContext';
import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';

async function request<T>(path = '', init?: RequestInit): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/personal-context${path}`, {
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
    ...init,
  });
  if (!response.ok) throw new Error(`个人上下文请求失败：HTTP ${response.status}`);
  const json = await response.json() as ApiResponse<T>;
  if (json.code !== 200 || !json.data) throw new Error(json.message || '个人上下文请求失败');
  return json.data;
}

export const fetchPersonalContext = () => request<PersonalContextSettings>();

export const updatePersonalContextProfile = (profile: string) => request<PersonalContextSettings>('/profile', {
  method: 'PUT',
  body: JSON.stringify({ profile }),
});

export const updatePersonalContextEnabled = (enabled: boolean) => request<PersonalContextSettings>('/enabled', {
  method: 'PUT',
  body: JSON.stringify({ enabled }),
});

export const clearPersonalContextProfile = () => request<PersonalContextSettings>('/profile', { method: 'DELETE' });

export const fetchProfileMaintenance = () => request<ProfileMaintenanceStatus>('/maintenance');

export const updateProfileMaintenanceEnabled = (enabled: boolean) => request<ProfileMaintenanceStatus>('/maintenance/enabled', {
  method: 'PUT',
  body: JSON.stringify({ enabled }),
});

export const checkProfileMaintenance = () => request<ProfileMaintenanceStatus>('/maintenance/check', {
  method: 'POST',
});

export const acceptProfileProposal = (proposalId: string, expectedRevision: string, profile: string) => request<PersonalContextSettings>(`/proposals/${encodeURIComponent(proposalId)}/accept`, {
  method: 'PUT',
  body: JSON.stringify({ expectedRevision, profile }),
});

export const rejectProfileProposal = (proposalId: string) => request<ProfileMaintenanceStatus>(`/proposals/${encodeURIComponent(proposalId)}`, {
  method: 'DELETE',
});
