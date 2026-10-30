/** 统计复盘的状态文案和请求辅助值，不持有页面状态。 */
export const STATE_TEXT: Record<string, string> = {
  PENDING: '排队中', RUNNING: '生成中', SUCCEEDED: '已完成', DEGRADED: '已降级',
  SKIPPED: '已跳过', UNKNOWN: '结果未知', PROPOSED: '待决定', ACCEPTED: '已采纳',
  DECLINED: '不适合', DEFERRED: '暂不决定', COMPLETED: '已完成', PARTIAL: '部分完成',
  NOT_DONE: '未完成', ARCHIVED: '已归档', CANDIDATE: '候选观察', CORRECTED: '已纠正', REJECTED: '已否定',
};

export function requestId(prefix: string) {
  return `${prefix}_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}
export function yesterday(): string {
  const value = new Date();
  value.setDate(value.getDate() - 1);
  return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
}
