import { useCallback, useEffect, useId, useRef, useState } from 'react';
import type { TaskReminderConfiguration, TaskReminderPreferences } from '../../types/taskReminder';
import { getTaskReminderConfiguration, importTaskReminderAudio, previewTaskRestLock, saveTaskReminderPreferences } from '../../services/taskReminderPreferences';
import { playReminderAudio } from '../../services/taskReminderAudio';
import { isTaskDesktop } from '../../services/taskDesktop';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { LeverSwitch } from '../common/LeverSwitch';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import styles from './TaskReminderSettings.module.css';

/** 全局提醒窗口的本机设置；修改自动保存，试听在离开页面时停止。 */
export function TaskReminderSettings() {
  const [config, setConfig] = useState<TaskReminderConfiguration | null>(null);
  const [interval, setInterval] = useState('4');
  const [busy, setBusy] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [lockPreviewing, setLockPreviewing] = useState(false);
  const [error, setError] = useState('');
  const preview = useRef<AbortController | null>(null);
  const lockPreviewTimer = useRef<number | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const soundHelpId = useId();
  const { showMessage } = useMessage();
  const desktop = isTaskDesktop();
  async function load() {
    setError('');
    try {
      const value = await getTaskReminderConfiguration();
      setConfig(value); setInterval(String(value.preferences.repeatSeconds));
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }

  const stopLockPreview = useCallback(() => {
    if (lockPreviewTimer.current) {
      window.clearTimeout(lockPreviewTimer.current);
      lockPreviewTimer.current = null;
    }
    setLockPreviewing(false);
    try { window?.localStorage?.removeItem('butvan-task-rest-data'); } catch {}
    void previewTaskRestLock(0).catch(() => {});
  }, []);

  useEffect(() => {
    let bc: BroadcastChannel | null = null;
    try {
      if (typeof BroadcastChannel !== 'undefined') {
        bc = new BroadcastChannel('butvan-task-rest-sync');
        bc.onmessage = (event) => {
          if (event.data?.type === 'EARLY_FINISH' || event.data?.type === 'FINISH') {
            stopLockPreview();
          }
        };
      }
    } catch {}
    return () => {
      bc?.close();
      stopLockPreview();
    };
  }, [stopLockPreview]);

  useEffect(() => { void load(); return () => { preview.current?.abort(); stopLockPreview(); }; }, [stopLockPreview]);
  const disabled = !desktop || busy || !config;
  const validInterval = /^\d+$/.test(interval) && Number(interval) >= 1 && Number(interval) <= 3600;
  const options = config?.sounds.map(sound => ({ value: sound.id, label: `${sound.name} · ${{ builtin: '内置', system: '系统', custom: '本机导入' }[sound.source]}` })) ?? [];
  const saving = useRef(false);

  async function handleToggleLockPreview() {
    if (lockPreviewing) {
      stopLockPreview();
      return;
    }
    const durationSec = 60;
    const payload = {
      runId: 'preview',
      startedAt: Date.now(),
      durationMs: durationSec * 1000,
      breakMinutes: 1,
      title: '久坐提醒（效果预览）',
      content: '当前为锁屏全屏效果预览，按 Esc 或点击卡片上的“提前结束”即可退出。',
    };
    try { window?.localStorage?.setItem('butvan-task-rest-data', JSON.stringify(payload)); } catch {}
    try {
      if (typeof BroadcastChannel !== 'undefined') {
        const bc = new BroadcastChannel('butvan-task-rest-sync');
        bc.postMessage({ type: 'START', payload });
        bc.close();
      }
    } catch {}

    setLockPreviewing(true);
    try {
      await previewTaskRestLock(durationSec);
      const timer = window.setTimeout(() => {
        stopLockPreview();
      }, durationSec * 1000);
      lockPreviewTimer.current = timer;
    } catch (e) {
      stopLockPreview();
      showMessage('error', `无法开启锁屏预览：${e instanceof Error ? e.message : String(e)}`);
    }
  }

  function stopPreview() { preview.current?.abort(); preview.current = null; setPlaying(false); }
  async function listen() {
    if (!config) return;
    stopPreview();
    const controller = new AbortController(); preview.current = controller; setPlaying(true);
    try { await playReminderAudio(config.preferences.soundId, controller.signal, 'preview'); }
    catch (e) { if (!controller.signal.aborted) showMessage('error', e instanceof Error ? e.message : String(e)); }
    finally { if (preview.current === controller) { preview.current = null; setPlaying(false); } }
  }
  async function upload(file: File) {
    stopPreview(); setBusy(true);
    try {
      const value = await importTaskReminderAudio(file);
      setConfig(value); showMessage('success', '音频已保存到本机并设为提示音');
    } catch (e) { showMessage('error', e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function save(patch: Partial<TaskReminderPreferences>) {
    if (!config || saving.current) return;
    const previous = config.preferences;
    const preferences = { ...previous, ...patch };
    if (JSON.stringify(previous) === JSON.stringify(preferences)) return;
    saving.current = true;
    setBusy(true);
    setConfig({ ...config, preferences });
    try {
      await saveTaskReminderPreferences(preferences);
    } catch (e) {
      setConfig({ ...config, preferences: previous });
      setInterval(String(previous.repeatSeconds));
      showMessage('error', `自动保存失败，请重试：${e instanceof Error ? e.message : String(e)}`);
    } finally { saving.current = false; setBusy(false); }
  }
  function saveInterval() {
    if (validInterval) void save({ repeatSeconds: Number(interval) });
  }

  return <SettingsGroup title="提醒窗口" description="修改自动保存在本机，下次提醒生效；系统通知横幅继续使用系统声音设置。">
    <SettingsRow label="休息时锁定桌面" description="仅在确认休息后的倒计时期间遮罩所有屏幕；结束后恢复。保留系统快捷键，不会锁定系统账户。"
      control={<div className={styles.lockRow}>
        <Button size="sm" variant="outline" disabled={disabled} onClick={() => void handleToggleLockPreview()}>
          {lockPreviewing ? '停止预览' : '预览锁屏效果'}
        </Button>
        <LeverSwitch checked={config?.preferences.restLock ?? false} disabled={disabled} label="休息时锁定桌面" showLabel={false}
          onChange={restLock => void save({ restLock })} />
      </div>} />
    <SettingsRow label="提示音" layout="stacked" description="选择内置音色、当前电脑的系统提示音或导入的音频；任务自身须开启声音。"
      control={<div className={styles.sounds}>
        <div className={styles.soundControls}>
          <Select aria-label="选择提示音" aria-describedby={soundHelpId} title={config?.sounds.find(sound => sound.id === config.preferences.soundId)?.name}
            appearance="ghost" containerClassName={styles.soundSelect} options={options} value={config?.preferences.soundId ?? ''} disabled={disabled} fieldSize="md" fullWidth
            onChange={event => { stopPreview(); const soundId = event.target.value; void save({ soundId }); }} />
          <div className={styles.actions}>
            <Button size="sm" disabled={disabled} onClick={() => playing ? stopPreview() : void listen()}>{playing ? '停止试听' : '试听'}</Button>
            <Button size="sm" disabled={disabled} onClick={() => fileInput.current?.click()}>导入音频</Button>
            <input ref={fileInput} className={styles.file} type="file" accept=".wav,.mp3,.m4a,.ogg" aria-label="导入提示音文件" disabled={disabled}
              onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void upload(file); }} />
          </div>
        </div>
        <span id={soundHelpId} className={styles.hint}>WAV、MP3、M4A、OGG，最大 10 MB。即使连接远程后端，也仅保存在当前电脑，不上传服务器；再次导入会替换原音频。</span>
      </div>} />
    <SettingsRow label="重复播放间隔" description="每段音频完整播放结束后，再等待指定秒数播放下一次；确认休息后停止。"
      control={<div className={styles.interval}><TextInput type="number" appearance="ghost" aria-label="重复播放间隔（秒）" min={1} max={3600} step={1} value={interval}
        disabled={disabled} invalid={!validInterval} onChange={event => setInterval(event.target.value)} onBlur={saveInterval}
        onKeyDown={event => { if (event.key === 'Enter') event.currentTarget.blur(); }} /><span>秒</span></div>} />
    {!validInterval && <p className={styles.error} role="alert">请输入 1 至 3600 的整数秒数</p>}
    {error && <p className={styles.error} role="alert">{error}<Button variant="ghost" onClick={() => void load()}>重新读取</Button></p>}
    {!desktop && <p className={styles.hint}>请在桌面应用中配置提醒窗口。</p>}
  </SettingsGroup>;
}
