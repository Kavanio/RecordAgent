import type { AutomationSpec } from '../../types/automation';
export function newAutomation(kind: AutomationSpec['kind'] = 'REMINDER'): AutomationSpec {
  return { title: kind === 'DAILY_REPORT' ? '每日日报' : kind === 'SEDENTARY' ? '久坐提醒' : '',
    content: kind === 'SEDENTARY' ? '已经连续使用电脑 45 分钟，起来活动一下吧。' : '', kind,
    trigger: kind === 'SEDENTARY' ? 'ACTIVITY' : 'DAILY', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    atTime: '20:30', onceAt: null, weekdays: 127, minutes: 45, breakMinutes: 5,
    windowStart: '00:00', windowEnd: '00:00', desktop: true, email: false, confirm: kind === 'SEDENTARY',
    sound: true, expense: true, todo: true, study: false, mailTemplate: { style: 'CARDS', accent: '#39756b', heading: '今日生活简报', introduction: '把一天的点滴，整理成值得回看的记录。', footer: '来自 ButvanAgent · 为每一天留下一点记录', body: '{{expense}}\n\n{{todo}}\n\n{{study}}' } };
}
export const taskStatus: Record<string, string> = { ENABLED: '已启用', PAUSED: '已暂停', FINISHED: '已结束', DELETED: '已删除' };
export const runStatus: Record<string, string> = { SUCCESS: '内容已生成', PARTIAL: '内容不完整', FAILED: '失败', SKIPPED: '已跳过', PENDING: '等待投递', CLAIMED: '正在提交', SENDING: '正在提交', SUBMITTED: '已提交', UNKNOWN: '结果未知', WAITING: '待确认', CONFIRMED: '已确认', WITHDRAWN: '已撤回', NONE: '无需确认' };
export function scheduleText(s: AutomationSpec): string {
  if (s.trigger === 'ACTIVITY') return `连续使用 ${s.minutes} 分钟`;
  if (s.trigger === 'INTERVAL') return `每隔 ${s.minutes} 分钟`;
  if (s.trigger === 'ONCE') return s.onceAt ? new Date(s.onceAt).toLocaleString('zh-CN') : '未设置时间';
  const days = ['一','二','三','四','五','六','日'].filter((_, i) => (s.weekdays & (1 << i)) !== 0);
  return `${s.weekdays === 127 ? '每天' : `每周${days.join('、')}`} ${s.atTime} · ${s.timezone}`;
}
