import type { LifeContent } from '../../types/lifeReview';
import { lifeFactLabel } from './lifeReviewLabels';

/** Java ZonedDateTime 附带的区域名称不属于浏览器日期语法，保留原偏移后转换。 */
export function lifeReviewTime(value: string, timezone: string): string {
  if (!value) return '时间未记录';
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return `${value}（归属日）`;
  const date = new Date(value.replace(/\[[^\]]+\]$/, '').replace(/(\.\d{3})\d+/, '$1'));
  if (!Number.isFinite(date.getTime())) return '时间未记录';
  try { return date.toLocaleString('zh-CN', { timeZone: timezone, hour12: false }); }
  catch { return '时区不可用'; }
}

export interface ReferenceEntry { id: string; text: string; time: string; truncated: boolean }
export interface ReferenceGroup { key: string; title: string; entries: ReferenceEntry[] }

/** 按来源而非内部编号分类；未知来源独立保留，避免静默丢失材料。 */
export function lifeReferenceGroups(content: LifeContent): ReferenceGroup[] {
  const groups: ReferenceGroup[] = [
    { key: 'CHAT', title: '聊天', entries: [] },
    { key: 'HISTORICAL_MEMORY', title: '关联往期经历', entries: [] },
    { key: 'JOURNAL', title: '每日手记', entries: [] },
    { key: 'PERSONAL', title: '目标与反馈', entries: [] },
    { key: 'SUPPLEMENT', title: '补充经历', entries: [] },
    { key: 'FINANCE', title: '财务', entries: [] },
    { key: 'STUDY', title: '学习', entries: [] },
    { key: 'TODO', title: '待办', entries: [] },
    { key: 'OTHER', title: '其他材料', entries: [] },
  ];
  const add = (key: string, entry: ReferenceEntry) => (groups.find(g => g.key === key) || groups[8]).entries.push(entry);
  for (const material of content.materials) {
    const key = ['USER_GOAL', 'USER_FEEDBACK'].includes(material.kind) ? 'PERSONAL' : material.kind;
    add(key, { id: material.id, text: material.text, time: material.occurredAt, truncated: material.truncated });
  }
  for (const fact of content.facts || []) {
    add(fact.domain, { id: fact.id, text: lifeFactLabel(fact), time: fact.period, truncated: false });
  }
  return groups.filter(g => g.entries.length > 0);
}

/** 历史版本的窗口取自自身快照，不使用当前配置重算。 */
export function lifeReviewWindow(coverage: string): string {
  const range = /窗口=(\d{4}-\d{2}-\d{2})至(\d{4}-\d{2}-\d{2})/.exec(coverage);
  return range ? `${range[1]} 至 ${range[2]}` : '此版本未记录明确窗口';
}
