import { apiFetch, apiResourceUrl, getApiBaseUrl, type ApiResponse } from './api';
import type { RecordAttachment, RecordDaySummary, RecordEntry, RecordReferencePage, RecordTab, SaveRecordInput } from '../types/record';

async function recordRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}${path}`, init);
  const payload = await response.json() as ApiResponse<T>;
  if (!response.ok || payload.code !== 200) throw new Error(payload.message || '资料请求失败');
  return payload.data;
}

const encode = (value: string) => encodeURIComponent(value);
const json = (method: string, body?: object): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: body ? JSON.stringify(body) : undefined,
});

/** 查询日期范围内的记录，筛选条件由后端统一解释。 */
export function fetchRecords(from: string, to: string, filters: { type?: string; tag?: string; query?: string; tabId?: string } = {}) {
  const params = new URLSearchParams({ from, to });
  if (filters.type) params.set('type', filters.type);
  if (filters.tag) params.set('tag', filters.tag);
  if (filters.query) params.set('query', filters.query);
  if (filters.tabId) params.set('tabId', filters.tabId);
  return recordRequest<RecordEntry[]>(`/agent/records?${params}`);
}

/** 查询月历轻量摘要。 */
export function fetchRecordDays(from: string, to: string) {
  return recordRequest<RecordDaySummary[]>(`/agent/records/days?from=${encode(from)}&to=${encode(to)}`);
}

/** 查询资料引用候选；响应不包含完整正文。 */
export function fetchRecordReferences(query = '', limit = 30, offset = 0) {
  const params = new URLSearchParams({ query, limit: String(limit), offset: String(offset) });
  return recordRequest<RecordReferencePage>(`/agent/records/references?${params}`);
}

export function fetchRecord(id: string) {
  return recordRequest<RecordEntry>(`/agent/records/${encode(id)}`);
}

export function createRecord(input: SaveRecordInput) {
  return recordRequest<RecordEntry>('/agent/records', json('POST', input));
}

export function updateRecord(id: string, version: number, input: SaveRecordInput) {
  return recordRequest<RecordEntry>(`/agent/records/${encode(id)}?expectedVersion=${version}`, json('PUT', input));
}

export function updateRecordFlags(id: string, version: number, flags: { pinned?: boolean; favorite?: boolean; archived?: boolean }) {
  return recordRequest<RecordEntry>(`/agent/records/${encode(id)}/flags`, json('PATCH', { ...flags, expectedVersion: version }));
}

export function trashRecord(id: string, version: number) {
  return recordRequest<string>(`/agent/records/${encode(id)}?expectedVersion=${version}`, json('DELETE'));
}

export function fetchRecordTrash() { return recordRequest<RecordEntry[]>('/agent/records/trash'); }
export function restoreRecord(id: string, version: number) {
  return recordRequest<RecordEntry>(`/agent/records/${encode(id)}/restore?expectedVersion=${version}`, json('POST'));
}
export function clearRecordTrash() { return recordRequest<number>('/agent/records/trash', json('DELETE')); }

export function fetchRecordTabs() { return recordRequest<RecordTab[]>('/agent/records/tabs'); }
export function createRecordTab(name: string) { return recordRequest<RecordTab>('/agent/records/tabs', json('POST', { name })); }
export function reorderRecordTabs(tabIds: string[]) {
  return recordRequest<RecordTab[]>('/agent/records/tabs/order', json('PUT', { tabIds }));
}
export function deleteRecordTab(tabId: string) { return recordRequest<string>(`/agent/records/tabs/${encode(tabId)}`, json('DELETE')); }

export function fetchRecordAttachments(recordId: string) {
  return recordRequest<RecordAttachment[]>(`/agent/records/${encode(recordId)}/attachments`);
}

export function uploadRecordAttachment(recordId: string, file: File) {
  const body = new FormData(); body.append('file', file);
  return recordRequest<RecordAttachment>(`/agent/records/${encode(recordId)}/attachments`, { method: 'POST', body });
}

export function attachmentContentUrl(recordId: string, attachmentId: string) {
  return apiResourceUrl(`/agent/records/${encode(recordId)}/attachments/${encode(attachmentId)}/content`);
}

/** 下载包含 JSON、Markdown、图片和附件原文件的完整备份。 */
export async function exportRecordBackup() {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/records/backup`);
  if (!response.ok) throw new Error('备份导出失败');
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a'); link.href = url; link.download = `butvan-records-${new Date().toISOString().slice(0, 10)}.zip`;
  link.click(); URL.revokeObjectURL(url);
}

/** 导入备份会由后端校验格式并完整替换当前资料库。 */
export async function importRecordBackup(file: File) {
  const body = new FormData(); body.append('file', file);
  return recordRequest<number>('/agent/records/backup', { method: 'POST', body });
}
