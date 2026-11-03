import { apiFetch, getApiBaseUrl } from './api';
import type { ApiResponse } from './api';
import type { SessionDetailDto, SessionSummaryDto } from '../types/chat';

/** 独立只读入口；错误必须显式展示，不能把旧后端或读取失败伪装为空列表。 */
async function read<T>(path: string): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}/agent/sessions/feishu${path}`, {
    signal: AbortSignal.timeout(10_000),
  });
  if (!response.ok) throw new Error(response.status === 404
    ? '无法读取飞书对话，请确认后端已更新并重启' : '读取飞书对话失败，请重试');
  const result: ApiResponse<T> = await response.json();
  if (result.code !== 200 || result.data == null) throw new Error('读取飞书对话失败，请重试');
  return result.data;
}

export async function fetchFeishuConversations(): Promise<SessionSummaryDto[]> {
  const sessions = await read<SessionSummaryDto[]>('');
  if (!Array.isArray(sessions)) throw new Error('飞书会话列表格式不正确，请更新后端');
  return sessions;
}

export function fetchFeishuConversation(id: string): Promise<SessionDetailDto> {
  return read(`/${encodeURIComponent(id)}`);
}
