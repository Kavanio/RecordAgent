/** 报告邮件点评的状态文案、周期枚举和请求辅助值，不持有页面状态。 */
export const STATE_TEXT: Record<string, string> = {
  PENDING: '排队中', RUNNING: '生成中', SUCCEEDED: '已完成', DEGRADED: '已降级',
  SKIPPED: '已跳过', UNKNOWN: '结果未知', PROPOSED: '待决定', ACCEPTED: '已采纳',
  DECLINED: '不适合', DEFERRED: '暂不决定', COMPLETED: '已完成', PARTIAL: '部分完成',
  NOT_DONE: '未完成', ARCHIVED: '已归档', CANDIDATE: '候选观察', CORRECTED: '已纠正', REJECTED: '已否定',
};

export const PERIODS: { id: 'DAILY_REPORT' | 'WEEKLY_REPORT' | 'MONTHLY_REPORT'; label: string }[] = [
  { id: 'DAILY_REPORT', label: '日报' }, { id: 'WEEKLY_REPORT', label: '周报' }, { id: 'MONTHLY_REPORT', label: '月报' },
];
export const DOMAINS: { id: 'FINANCE' | 'TODO' | 'STUDY'; label: string }[] = [
  { id: 'FINANCE', label: '财务统计' }, { id: 'TODO', label: '待办' }, { id: 'STUDY', label: '学习' },
];
export const DECISIONS: { id: 'ACCEPTED' | 'DECLINED' | 'DEFERRED'; label: string }[] = [
  { id: 'ACCEPTED', label: '采纳' }, { id: 'DECLINED', label: '不适合' }, { id: 'DEFERRED', label: '暂不决定' },
];
export const RESULTS: { id: 'COMPLETED' | 'PARTIAL' | 'NOT_DONE' | 'UNKNOWN'; label: string }[] = [
  { id: 'COMPLETED', label: '完成' }, { id: 'PARTIAL', label: '部分完成' }, { id: 'NOT_DONE', label: '未完成' }, { id: 'UNKNOWN', label: '无法判断' },
];

export function requestId(prefix: string) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}
export function yesterday(): string {
  const value = new Date();
  value.setDate(value.getDate() - 1);
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
}
