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
