import type { SaveStudySessionInput, StudySession, StudyStatistics } from '../types/study';
import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';

async function studyRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await apiFetch(`${getApiBaseUrl()}${path}`, init);
  let payload: ApiResponse<T>;
  try {
    payload = await response.json() as ApiResponse<T>;
  } catch {
    throw new Error(`学习记录服务返回了无法识别的响应（HTTP ${response.status}）`);
  }
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.message || `学习记录请求失败（HTTP ${response.status}）`);
  }
  return payload.data;
}

const jsonInit = (method: 'POST' | 'PUT', body: object): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

/** 查询用户使用过的学习分类。 */
export function fetchStudyCategories(): Promise<string[]> {
  return studyRequest<string[]>('/agent/study-sessions/categories');
}

/** 立即开始一段项目内学习。 */
export function startStudySession(content: string, category: string, timezone: string): Promise<StudySession> {
  return studyRequest<StudySession>('/agent/study-sessions/start', jsonInit('POST', { content, category, timezone }));
}

/** 结束指定的进行中学习。 */
export function finishStudySession(id: string, expectedVersion: number): Promise<StudySession> {
  return studyRequest<StudySession>(
    `/agent/study-sessions/${encodeURIComponent(id)}/finish?expectedVersion=${expectedVersion}`,
    { method: 'POST' },
  );
}

/** 补录一段完整学习时段。 */
export function createManualStudySession(input: SaveStudySessionInput): Promise<StudySession> {
  return studyRequest<StudySession>('/agent/study-sessions/manual', jsonInit('POST', input));
}

/** 覆盖修改一段已结束学习时段。 */
export function updateStudySession(
  id: string,
  expectedVersion: number,
  input: SaveStudySessionInput,
): Promise<StudySession> {
  return studyRequest<StudySession>(
    `/agent/study-sessions/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`,
    jsonInit('PUT', input),
  );
}

/** 删除一段学习记录。 */
export function deleteStudySession(id: string, expectedVersion: number): Promise<void> {
  return studyRequest<string>(
    `/agent/study-sessions/${encodeURIComponent(id)}?expectedVersion=${expectedVersion}`,
    { method: 'DELETE' },
  ).then(() => undefined);
}

/** 查询与本地日期范围相交的学习时段。 */
export function fetchStudySessions(
  from: string,
  to: string,
  timezone: string,
): Promise<StudySession[]> {
  const params = new URLSearchParams({ from, to, timezone });
  return studyRequest<StudySession[]>(`/agent/study-sessions?${params}`);
}

/** 查询由后端统一计算的学习时长统计。 */
export function fetchStudyStatistics(
  from: string,
  to: string,
  timezone: string,
): Promise<StudyStatistics> {
  const params = new URLSearchParams({ from, to, timezone });
  return studyRequest<StudyStatistics>(`/agent/study-sessions/statistics?${params}`);
}
