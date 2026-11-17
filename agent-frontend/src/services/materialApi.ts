import type { ChatMaterialFilter, ChatMaterialItem, ChatMaterialPage, ChatMaterialSummary } from '../types/material';
import { apiFetch, apiResourceUrl, getApiBaseUrl, type ApiResponse } from './api';

async function unwrap<T>(response: Response): Promise<T> {
  const payload: ApiResponse<T> = await response.json();
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.message || '档案请求失败');
  }
  return payload.data;
}

export async function fetchMaterials(filter: ChatMaterialFilter = {}): Promise<ChatMaterialPage> {
  const params = new URLSearchParams();
  if (filter.category) params.set('category', filter.category);
  if (filter.subType) params.set('subType', filter.subType);
  if (filter.channel) params.set('channel', filter.channel);
  if (filter.sessionKind) params.set('sessionKind', filter.sessionKind);
  if (filter.sessionId) params.set('sessionId', filter.sessionId);
  if (filter.from) params.set('from', filter.from);
  if (filter.to) params.set('to', filter.to);
  if (filter.query) params.set('query', filter.query);
  if (filter.limit !== undefined) params.set('limit', String(filter.limit));
  if (filter.offset !== undefined) params.set('offset', String(filter.offset));
  const queryText = params.toString();
  const response = await apiFetch(`${getApiBaseUrl()}/agent/materials${queryText ? `?${queryText}` : ''}`);
  return unwrap<ChatMaterialPage>(response);
}

export async function fetchMaterialSummary(): Promise<ChatMaterialSummary> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/materials/summary`);
  return unwrap<ChatMaterialSummary>(response);
}

export async function fetchMaterialDetail(fileId: string): Promise<ChatMaterialItem> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/materials/${encodeURIComponent(fileId)}`);
  return unwrap<ChatMaterialItem>(response);
}

export async function deleteMaterial(fileId: string): Promise<void> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/materials/${encodeURIComponent(fileId)}`, {
    method: 'DELETE',
  });
  await unwrap<string>(response);
}

export function materialContentUrl(fileId: string, download = false): string {
  const suffix = download ? '?download=true' : '';
  return apiResourceUrl(`/agent/materials/${encodeURIComponent(fileId)}/content${suffix}`);
}
