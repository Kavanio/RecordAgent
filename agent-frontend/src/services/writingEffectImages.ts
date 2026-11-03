import type { WritingEffectImage } from '../types/writingEffects';
import { MAX_EFFECT_IMAGE_URL_LENGTH } from './writingEffectPreferences';

const MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
const THUMBNAIL_SIZE = 48;

function readBytes(file: File): Promise<Uint8Array<ArrayBuffer>> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('图片读取失败，请重新选择'));
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.readAsArrayBuffer(file);
  });
}

/** 校验实际文件头并解码，统一归一化为 48×48 PNG；不保留原图、路径或远程链接。 */
export async function importWritingEffectImage(file: File): Promise<WritingEffectImage> {
  if (!file.size || file.size > MAX_UPLOAD_BYTES) throw new Error('请选择不超过 2 MB 的图片');
  const bytes = await readBytes(file);
  const png = [137, 80, 78, 71, 13, 10, 26, 10].every((value, index) => bytes[index] === value);
  const jpeg = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  const header = new TextDecoder('ascii').decode(bytes.slice(0, 12));
  const gif = header.startsWith('GIF87a') || header.startsWith('GIF89a');
  const webp = header.startsWith('RIFF') && header.slice(8, 12) === 'WEBP';
  if (!png && !jpeg && !gif && !webp) throw new Error('仅支持 PNG、JPEG、WebP 或 GIF 图片');
  const url = URL.createObjectURL(new Blob([bytes], { type: png ? 'image/png' : jpeg ? 'image/jpeg' : gif ? 'image/gif' : 'image/webp' }));
  try {
    const image = await new Promise<HTMLImageElement>((resolve, reject) => {
      const element = new Image();
      element.onload = () => resolve(element);
      element.onerror = () => reject(new Error('图片无法解码，请换一张图片'));
      element.src = url;
    });
    const { naturalWidth: width, naturalHeight: height } = image;
    if (!width || !height || width > 8192 || height > 8192 || width * height > 16_777_216) throw new Error('图片分辨率过大，请选择较小的图片');
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = THUMBNAIL_SIZE;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('当前环境无法处理图片');
    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    const scale = THUMBNAIL_SIZE / Math.max(width, height);
    const drawWidth = width * scale, drawHeight = height * scale;
    context.drawImage(image, (THUMBNAIL_SIZE - drawWidth) / 2, (THUMBNAIL_SIZE - drawHeight) / 2, drawWidth, drawHeight);
    const dataUrl = canvas.toDataURL('image/png');
    if (!dataUrl.startsWith('data:image/png;base64,') || dataUrl.length > MAX_EFFECT_IMAGE_URL_LENGTH) throw new Error('缩小后的图片仍然过大，请换一张图片');
    return { id: crypto.randomUUID(), name: file.name.slice(0, 80), dataUrl };
  } finally {
    URL.revokeObjectURL(url);
  }
}
