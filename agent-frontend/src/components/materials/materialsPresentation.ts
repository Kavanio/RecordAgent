import type { ChatMaterialItem } from '../../types/material';

export function formatMaterialBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function formatMaterialDateTime(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return iso;
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(parsed);
}

export function channelLabel(channel: string): string {
  switch (channel?.toUpperCase()) {
    case 'DESKTOP':
      return '桌面端';
    case 'FEISHU':
      return '飞书';
    default:
      return channel || '未知渠道';
  }
}

export function sessionKindLabel(sessionKind: string): string {
  switch (sessionKind?.toUpperCase()) {
    case 'GENERAL':
      return '普通对话';
    case 'PROJECT':
      return '项目对话';
    default:
      return sessionKind || '未分类';
  }
}

export function categoryLabel(category: string): string {
  switch (category?.toUpperCase()) {
    case 'IMAGE':
      return '图片';
    case 'DOCUMENT':
      return '文档';
    case 'AUDIO':
      return '音频';
    default:
      return '其他';
  }
}

export function shortFileId(fileId: string): string {
  if (!fileId) return '';
  return fileId.length > 12 ? `${fileId.slice(0, 8)}…${fileId.slice(-4)}` : fileId;
}

export function isImageMaterial(item: ChatMaterialItem): boolean {
  return item.category?.toUpperCase() === 'IMAGE' || item.mediaType?.startsWith('image/');
}

export interface MaterialDateGroup {
  dateKey: string;
  label: string;
  items: ChatMaterialItem[];
}

function toLocalDateKey(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function formatMaterialDateHeading(dateStr: string, now: Date = new Date()): string {
  if (!dateStr || !/^\d{4}-\d{2}-\d{2}$/.test(dateStr)) {
    return dateStr || '更早';
  }
  const todayKey = toLocalDateKey(now);
  if (dateStr === todayKey) return '今天';

  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (dateStr === toLocalDateKey(yesterday)) return '昨天';

  const [yearStr, monthStr, dayStr] = dateStr.split('-');
  const year = Number(yearStr);
  const month = Number(monthStr);
  const day = Number(dayStr);
  if (year === now.getFullYear()) {
    return `${month}月${day}日`;
  }
  return `${year}年${month}月${day}日`;
}

export function groupMaterialsByDate(items: ChatMaterialItem[], now: Date = new Date()): MaterialDateGroup[] {
  const groups = new Map<string, ChatMaterialItem[]>();
  for (const item of items) {
    const key = item.createdDate || 'unknown';
    const bucket = groups.get(key);
    if (bucket) {
      bucket.push(item);
    } else {
      groups.set(key, [item]);
    }
  }
  return Array.from(groups.entries()).map(([dateKey, groupItems]) => ({
    dateKey,
    label: dateKey === 'unknown' ? '更早' : formatMaterialDateHeading(dateKey, now),
    items: groupItems,
  }));
}
