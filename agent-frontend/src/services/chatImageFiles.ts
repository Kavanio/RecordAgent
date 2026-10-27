import type { ChatImageAttachment } from '../types/chat';

/** 读取浏览器选择/粘贴的图片，不上传、不落盘；真实格式仍由服务端校验。 */
export function readChatImageFile(file: File, mediaType: string): Promise<ChatImageAttachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('图片读取失败，请重试。'));
    reader.onabort = () => reject(new Error('图片读取已取消。'));
    reader.onload = () => {
      if (typeof reader.result !== 'string' || !reader.result.includes(',')) {
        reject(new Error('图片读取失败，请重试。'));
        return;
      }
      // 部分剪贴板文件缺少 MIME，FileReader 会生成 octet-stream；统一为已校验的图片类型。
      resolve({ mediaType, dataUrl: `data:${mediaType};base64,${reader.result.split(',')[1]}`, name: file.name });
    };
    reader.readAsDataURL(file);
  });
}
