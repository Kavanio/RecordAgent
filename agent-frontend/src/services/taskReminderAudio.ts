import { invoke, isTauri } from '@tauri-apps/api/core';

type SoundChannel = 'reminder' | 'preview';
const abortError = () => new DOMException('播放已停止', 'AbortError');

/** 可中断等待，重复间隔只在上一段音频真正结束后开始。 */
export function waitForReminder(milliseconds: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(abortError()); return; }
    const cancel = () => { clearTimeout(timer); reject(abortError()); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', cancel); resolve(); }, milliseconds);
    signal.addEventListener('abort', cancel, { once: true });
  });
}

/** 原创合成音色生成短 WAV；不依赖网络或系统音频库。 */
function builtinAudio(id: string): Uint8Array<ArrayBuffer> {
  const notes: Record<string, number[]> = { bell: [880, 1320], chime: [523, 659, 784], pulse: [740, 740], rise: [440, 554, 659, 880], soft: [523] };
  const frequencies = notes[id.slice('builtin:'.length)];
  if (!frequencies) throw new Error('提示音不存在');
  const rate = 22050, noteLength = 0.34, length = Math.floor(rate * noteLength * frequencies.length);
  const buffer = new ArrayBuffer(44 + length * 2), view = new DataView(buffer);
  const text = (at: number, value: string) => [...value].forEach((char, index) => view.setUint8(at + index, char.charCodeAt(0)));
  text(0, 'RIFF'); view.setUint32(4, 36 + length * 2, true); text(8, 'WAVE'); text(12, 'fmt ');
  view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
  view.setUint32(24, rate, true); view.setUint32(28, rate * 2, true); view.setUint16(32, 2, true); view.setUint16(34, 16, true);
  text(36, 'data'); view.setUint32(40, length * 2, true);
  for (let i = 0; i < length; i++) {
    const t = i / rate, note = Math.min(frequencies.length - 1, Math.floor(t / noteLength));
    const local = t - note * noteLength;
    const envelope = Math.min(1, local / 0.008) * Math.exp(-local * 10) * Math.min(1, (noteLength - local) / 0.025);
    view.setInt16(44 + i * 2, Math.round(Math.sin(2 * Math.PI * frequencies[note] * local) * envelope * 11000), true);
  }
  return new Uint8Array(buffer);
}

function playBlob(bytes: Uint8Array<ArrayBuffer>, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(abortError()); return; }
    const url = URL.createObjectURL(new Blob([bytes]));
    const audio = new Audio(url);
    const clean = () => {
      clearTimeout(loadTimeout);
      signal.removeEventListener('abort', cancel);
      audio.onended = null; audio.onerror = null; audio.onplaying = null;
      audio.pause(); audio.removeAttribute('src'); audio.load(); URL.revokeObjectURL(url);
    };
    const fail = (error: unknown) => { clean(); reject(error); };
    const cancel = () => fail(abortError());
    const loadTimeout = setTimeout(() => fail(new Error('音频加载超时，请重新选择提示音')), 15000);
    signal.addEventListener('abort', cancel, { once: true });
    audio.onended = () => { clean(); resolve(); };
    audio.onerror = () => fail(new Error('音频播放失败，请重新选择提示音'));
    audio.onplaying = () => clearTimeout(loadTimeout);
    void audio.play().catch(fail);
  });
}

/** 试听与正式提醒隔离；macOS 通过原生播放器查询播放结束，其他平台监听 ended。 */
async function playAudio(id: string, signal: AbortSignal, channel: SoundChannel = 'reminder'): Promise<void> {
  if (!isTauri() || signal.aborted) return;
  const native = await invoke<boolean>('task_reminder_audio', { id, action: 'supported', channel });
  if (signal.aborted) return;
  if (native) {
    const command = (action: string) => invoke<boolean>('task_reminder_audio', {
      id, action, channel, bytes: action === 'play' && id.startsWith('builtin:') ? Array.from(builtinAudio(id)) : undefined,
    });
    try {
      await command('play');
      if (signal.aborted) throw abortError();
      while (await command('status')) await waitForReminder(100, signal);
    } finally { await command('stop'); }
    return;
  }
  const bytes = id.startsWith('builtin:') ? builtinAudio(id)
    : new Uint8Array(await invoke<number[]>('read_task_reminder_audio', { id }));
  await playBlob(bytes, signal);
}

// 同一窗口同一通道串行播放，前一次取消的清理完成后才允许下一段启动。
const channels: Record<SoundChannel, Promise<void>> = { reminder: Promise.resolve(), preview: Promise.resolve() };
export function playReminderAudio(id: string, signal: AbortSignal, channel: SoundChannel = 'reminder'): Promise<void> {
  const playback = channels[channel].then(() => playAudio(id, signal, channel));
  channels[channel] = playback.catch(() => {});
  return playback;
}

/** 注入播放/等待能力便于验证非重叠时序；取消会同时停止正在播放的片段和等待。 */
export async function repeatReminderAudio(
  id: string, repeatSeconds: number, signal: AbortSignal,
  play = playReminderAudio, wait = waitForReminder,
): Promise<void> {
  while (!signal.aborted) {
    await play(id, signal);
    await wait(repeatSeconds * 1000, signal);
  }
}
