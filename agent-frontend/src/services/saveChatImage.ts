import { invoke, isTauri } from '@tauri-apps/api/core';

export async function saveChatImage(src: string, blob?: Blob): Promise<boolean> {
  const data = blob ?? await (await fetch(src)).blob();
  if (isTauri()) {
    return invoke<boolean>('save_chat_image', await data.arrayBuffer());
  }
  const extension = data.type === 'image/jpeg' ? 'jpg' : data.type.split('/')[1] || 'png';
  const link = document.createElement('a');
  link.href = src;
  link.download = `chat-image.${extension}`;
  link.click();
  return true;
}
