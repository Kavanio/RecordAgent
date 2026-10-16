import type { TaskDto } from '../types/team';
import { apiEventSourceUrl, apiFetch, getApiBaseUrl } from './api';

/** 单机桌面版的后端当前使用这个稳定身份隔离 AgentScope 工作区。 */
const LOCAL_USER_ID = 'local-default';

function taskUrl(sessionId: string, taskId?: string): string {
  const path = taskId ? `/api/tasks/${encodeURIComponent(taskId)}` : '/api/tasks';
  const query = new URLSearchParams({ userId: LOCAL_USER_ID, sessionId });
  return `${getApiBaseUrl()}${path}?${query.toString()}`;
}

function taskStreamUrl(sessionId: string): string {
  const query = new URLSearchParams({ userId: LOCAL_USER_ID, sessionId });
  return `${getApiBaseUrl()}/api/tasks/stream?${query.toString()}`;
}

function isTaskDto(value: unknown): value is TaskDto {
  if (typeof value !== 'object' || value === null) return false;
  const task = value as Record<string, unknown>;
  return ['taskId', 'status', 'result', 'error'].every((key) => typeof task[key] === 'string');
}

function isTaskDtoList(value: unknown): value is TaskDto[] {
  return Array.isArray(value) && value.every(isTaskDto);
}

/** 读取当前会话的后台子 Agent 任务。 */
export async function fetchSubagentTasks(sessionId: string): Promise<TaskDto[]> {
  const response = await apiFetch(taskUrl(sessionId));
  if (!response.ok) throw new Error(`读取子 Agent 任务失败：HTTP ${response.status}`);

  const data: unknown = await response.json();
  if (!isTaskDtoList(data)) {
    throw new Error('读取子 Agent 任务失败：响应格式不正确');
  }
  return data;
}

/**
 * 订阅当前会话的后台任务状态。服务端会先发送 snapshot，随后只推送发生变化的 task。
 */
export function subscribeSubagentTaskEvents(
  sessionId: string,
  handlers: {
    onSnapshot: (tasks: TaskDto[]) => void;
    onTask: (task: TaskDto) => void;
    onError?: () => void;
  },
): () => void {
  const eventSource = new EventSource(apiEventSourceUrl(taskStreamUrl(sessionId)));

  eventSource.addEventListener('snapshot', (event) => {
    try {
      const payload: unknown = JSON.parse((event as MessageEvent<string>).data);
      if (!isTaskDtoList(payload)) throw new Error('invalid snapshot');
      handlers.onSnapshot(payload);
    } catch {
      handlers.onError?.();
    }
  });

  eventSource.addEventListener('task', (event) => {
    try {
      const payload: unknown = JSON.parse((event as MessageEvent<string>).data);
      if (!isTaskDto(payload)) throw new Error('invalid task');
      handlers.onTask(payload);
    } catch {
      handlers.onError?.();
    }
  });

  eventSource.onerror = () => handlers.onError?.();
  return () => eventSource.close();
}

/** 取消仍在执行的后台子 Agent 任务。 */
export async function cancelSubagentTask(sessionId: string, taskId: string): Promise<void> {
  const response = await apiFetch(taskUrl(sessionId, taskId), { method: 'POST' });
  if (!response.ok) throw new Error(`取消子 Agent 任务失败：HTTP ${response.status}`);

  const data: unknown = await response.json();
  if (
    typeof data !== 'object' || data === null || !('cancelled' in data) || data.cancelled !== true
  ) {
    throw new Error('取消子 Agent 任务未成功');
  }
}
