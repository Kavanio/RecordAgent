import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';
import type {
  MemoryAuthorization, MemoryDocumentCommand, MemoryDocumentDetail,
  MemoryDocumentPage, MemoryOperation, MemorySettings,
} from '../types/experienceMemory';

async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/memory${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(`经历记忆请求失败：HTTP ${response.status}`);
  const result = await response.json() as ApiResponse<T>;
  if (result.code !== 200 || result.data == null) throw new Error(result.message || '经历记忆请求失败');
  return result.data;
}

export const fetchMemorySettings = () => request<MemorySettings>('/settings');
export const saveMemorySettings = (settings: MemorySettings, autoSave: boolean, autoRecall: boolean) =>
  request<MemorySettings>('/settings', 'PUT', { expectedVersion: settings.version, autoSave, autoRecall });
export const authorizeMemory = (input: MemoryAuthorization) =>
  request<{ revision: number; effectiveAt: string }>('/authorization', 'POST', input);
export const revokeMemory = (expectedRevision: number, requestId: string) =>
  request<{ revision: number; effectiveAt: string }>('/authorization', 'DELETE', { expectedRevision, requestId });
export const fetchMemoryDocuments = (cursor = '0') =>
  request<MemoryDocumentPage>(`/documents?cursor=${encodeURIComponent(cursor)}&limit=20`);
export const fetchMemoryDocument = (id: string) => request<MemoryDocumentDetail>(`/documents/${encodeURIComponent(id)}`);
export const correctMemoryDocument = (id: string, input: MemoryDocumentCommand & { correctedText: string }) =>
  request<MemoryOperation>(`/documents/${encodeURIComponent(id)}/corrections`, 'POST', input);
export const deleteMemoryDocument = (id: string, input: MemoryDocumentCommand) =>
  request<MemoryOperation>(`/documents/${encodeURIComponent(id)}`, 'DELETE', input);
export const fetchMemoryOperation = (id: string) => request<MemoryOperation>(`/operations/${encodeURIComponent(id)}`);
export const retryMemoryOperation = (id: string) => request<MemoryOperation>(`/operations/${encodeURIComponent(id)}/retry`, 'POST');
