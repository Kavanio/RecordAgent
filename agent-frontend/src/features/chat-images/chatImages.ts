import type { ChatImageAttachment } from '../../types/chat';

/** 与服务端 AgentImages 契约一致：编码前限制每轮原始字节总量。 */
export const MAX_CHAT_IMAGES = 4;
export const MAX_CHAT_IMAGE_BYTES = 10 * 1024 * 1024;
export const MAX_CHAT_IMAGE_TOTAL_BYTES = 12 * 1024 * 1024;
export const CHAT_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

/** Data URL 的原始字节数，用于草稿总预算，不额外解码或复制图片。 */
export function chatImageBytes(image: ChatImageAttachment): number {
  const data = image.dataUrl.slice(image.dataUrl.indexOf(',') + 1);
  const padding = data.endsWith('==') ? 2 : data.endsWith('=') ? 1 : 0;
  return Math.floor(data.length * 3 / 4) - padding;
}

/** MIME 缺失时仅用扩展名作前端提示，服务端仍以文件头权威判断。 */
export function validateChatImageFile(file: File, images: ChatImageAttachment[]): string {
  const extensions: Record<string, string> = {
    png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', webp: 'image/webp', gif: 'image/gif',
  };
  const type = file.type || extensions[file.name.split('.').pop()?.toLowerCase() ?? ''];
  if (!CHAT_IMAGE_TYPES.includes(type)) throw new Error('仅支持 PNG、JPEG、WebP 与 GIF 图片。');
  if (file.size === 0) throw new Error('图片内容不能为空。');
  if (file.size > MAX_CHAT_IMAGE_BYTES) throw new Error('单张图片不能超过 10 MiB。');
  if (images.length >= MAX_CHAT_IMAGES) throw new Error('单次最多发送 4 张图片。');
  if (images.reduce((sum, image) => sum + chatImageBytes(image), file.size) > MAX_CHAT_IMAGE_TOTAL_BYTES) {
    throw new Error('每轮图片总大小不能超过 12 MiB。');
  }
  return type;
}
