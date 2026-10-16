import type { DailyInsight } from '../types/dailyInsight';
import { formatLocalDate } from './dailyEvents';
import { apiFetch, getApiBaseUrl, type ApiResponse } from './api';

/** 读取由后端统一计算的每日洞察，前端只负责传递本地日期与时区。 */
export async function fetchDailyInsight(date = formatLocalDate(new Date())): Promise<DailyInsight> {
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const params = new URLSearchParams({ date, timezone });
  const response = await apiFetch(`${getApiBaseUrl()}/agent/insights/daily?${params}`);
  let payload: ApiResponse<DailyInsight>;
  try {
    payload = await response.json() as ApiResponse<DailyInsight>;
  } catch {
    throw new Error(`每日洞察服务返回了无法识别的响应（HTTP ${response.status}）`);
  }
  if (!response.ok || payload.code !== 200) {
    throw new Error(payload.message || `每日洞察读取失败（HTTP ${response.status}）`);
  }
  return payload.data;
}
