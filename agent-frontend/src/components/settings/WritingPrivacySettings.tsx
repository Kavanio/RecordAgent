import { useState } from 'react';
import { isPrivacyShortcutKey, privacyShortcutLabel, updateWritingPrivacyPreferences } from '../../services/writingPrivacyPreferences';
import type { WritingPrivacyPreferences } from '../../types/writingPrivacy';
import { Button } from '../common/Button';
import { LeverSwitch } from '../common/LeverSwitch';
import { useMessage } from '../common/Message';
import { useWritingPrivacyPreferences } from '../common/useWritingPrivacyPreferences';
import { SettingsGroup, SettingsRow } from './SettingsGroup';
import styles from './WritingPrivacySettings.module.css';

/** 资料写作防偷窥：共享开关与应用内快捷键，不注册系统全局快捷键。 */
export function WritingPrivacySettings() {
  const preferences = useWritingPrivacyPreferences();
  const { showMessage } = useMessage();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const save = async (patch: Partial<WritingPrivacyPreferences>) => {
    setSaving(true);
    try { await updateWritingPrivacyPreferences(patch); setError(''); }
    catch (reason) { showMessage('error', reason instanceof Error ? reason.message : '防偷窥设置保存失败'); }
    finally { setSaving(false); }
  };
  return <SettingsGroup title="写作防偷窥" description="仅在资料编写页生效。标题、目录和正文逐块模糊；悬浮只显示当前块，键盘输入时显示当前段落。">
    <SettingsRow label="开启防偷窥" description="记住开关状态，下次打开资料编辑页继续生效。也可在编辑页顶栏一键恢复或隐藏。" control={
      <LeverSwitch label="开启防偷窥" showLabel={false} checked={preferences.enabled} disabled={saving}
        onChange={enabled => void save({ enabled })} />
    } />
    <SettingsRow label="切换快捷键" labelFor="writing-privacy-shortcut" layout="stacked"
      description="点击输入框后按 ⌘/Ctrl + Shift + 字母设置；仅在资料编辑页内切换，不影响普通文字输入。" control={
      <div className={styles.shortcut}>
        <input id="writing-privacy-shortcut" aria-label="防偷窥快捷键" data-privacy-shortcut readOnly disabled={saving}
          value={privacyShortcutLabel(preferences.shortcut)} aria-invalid={Boolean(error)} aria-describedby={error ? 'writing-privacy-error' : undefined}
          onKeyDown={event => {
            if (event.key === 'Tab' || event.key === 'Escape' || event.nativeEvent.isComposing) return;
            event.preventDefault(); event.stopPropagation();
            if (event.repeat || ['Meta', 'Control', 'Shift', 'Alt'].includes(event.key)) return;
            const key = event.key.toLowerCase();
            if (!(event.ctrlKey || event.metaKey) || !event.shiftKey || event.altKey || !isPrivacyShortcutKey(key)) {
              setError('请按 ⌘/Ctrl + Shift + 字母；N、P、Q、R、S、T、W、Z 保留给常用操作'); return;
            }
            void save({ shortcut: key });
          }} />
        <Button variant="outline" disabled={saving || !preferences.shortcut} onClick={() => void save({ shortcut: '' })}>清除快捷键</Button>
        <Button variant="ghost" disabled={saving || preferences.shortcut === 'h'} onClick={() => void save({ shortcut: 'h' })}>恢复默认</Button>
        {error && <span id="writing-privacy-error" className={styles.error} role="alert">{error}</span>}
      </div>
    } />
  </SettingsGroup>;
}
