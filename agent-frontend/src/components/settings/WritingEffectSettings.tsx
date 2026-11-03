import { useRef, useState } from 'react';
import { ImagePlus, Play, Trash2 } from 'lucide-react';
import type { WritingEffectPreferences, WritingEffectStyle } from '../../types/writingEffects';
import { importWritingEffectImage } from '../../services/writingEffectImages';
import { MAX_EFFECT_IMAGES, updateWritingEffectPreferences, WRITING_EFFECT_EMOJIS } from '../../services/writingEffectPreferences';
import { Button } from '../common/Button';
import { LeverSwitch } from '../common/LeverSwitch';
import { Select } from '../common/Select';
import { useMessage } from '../common/Message';
import { useWritingEffectPreferences } from '../common/useWritingEffectPreferences';
import { WritingBurstParticles } from '../common/WritingBurstParticles';
import { SettingsGroup, SettingsRow } from './SettingsGroup';
import styles from './WritingEffectSettings.module.css';

/** 资料设置中的独立码字特效分组，统一管理图案、已缩小的本机图片与预览。 */
export function WritingEffectSettings() {
  const preferences = useWritingEffectPreferences();
  const { showMessage } = useMessage();
  const [saving, setSaving] = useState(false);
  const [preview, setPreview] = useState(0);
  const uploadInput = useRef<HTMLInputElement>(null);

  const save = async (patch: Partial<WritingEffectPreferences>) => {
    setSaving(true);
    try { await updateWritingEffectPreferences(patch); }
    catch (reason) { showMessage('error', reason instanceof Error ? reason.message : '特效设置保存失败'); }
    finally { setSaving(false); }
  };

  const upload = async (file: File) => {
    setSaving(true);
    try {
      if (preferences.images.length >= MAX_EFFECT_IMAGES) throw new Error('最多添加 8 张特效图片，请先删除部分图片');
      const image = await importWritingEffectImage(file);
      await updateWritingEffectPreferences({ images: [...preferences.images, image] });
      showMessage('success', '特效图片已添加，混合模式会自动使用');
    } catch (reason) { showMessage('error', reason instanceof Error ? reason.message : '图片添加失败'); }
    finally { setSaving(false); }
  };

  const toggleEmoji = (emoji: string) => {
    const next = preferences.emojis.includes(emoji)
      ? preferences.emojis.filter(item => item !== emoji) : [...preferences.emojis, emoji];
    if (!next.length) { showMessage('info', '请至少保留一个 emoji'); return; }
    void save({ emojis: next });
  };

  return <SettingsGroup title="打字特效" description="在资料与日历手记正文输入时，让小图案从刚输入的文字末尾散开。修改后自动保存。">
    <SettingsRow label="开启打字特效" description="系统开启“减少动态效果”时会暂停播放。" control={
      <LeverSwitch label="开启打字特效" showLabel={false} checked={preferences.enabled} disabled={saving}
        onChange={enabled => void save({ enabled })} />
    } />
    <SettingsRow label="图案风格" labelFor="writing-effect-style" description="混合模式会交替使用彩色图标、emoji 和已上传图片。" control={
      <Select id="writing-effect-style" value={preferences.style} disabled={saving}
        onChange={event => void save({ style: event.target.value as WritingEffectStyle })} options={[
          { label: '缤纷混合', value: 'mixed' }, { label: '彩色图标', value: 'icons' },
          { label: '可爱 emoji', value: 'emoji' }, { label: '我的图片', value: 'images', disabled: !preferences.images.length },
        ]} />
    } />
    <SettingsRow label="喜欢的 emoji" layout="stacked" description="点选要参与特效的图案，每次输入会变换组合。" control={
      <div className={styles.choices} aria-label="特效 emoji">
        {WRITING_EFFECT_EMOJIS.map(emoji => <Button key={emoji} variant="ghost" className={styles.emojiButton} disabled={saving}
          aria-label={`特效图案 ${emoji}`} aria-pressed={preferences.emojis.includes(emoji)} onClick={() => toggleEmoji(emoji)}>{emoji}</Button>)}
      </div>
    } />
    <SettingsRow label="我的图片" layout="stacked" description="支持 PNG、JPEG、WebP、GIF（静态图），每张不超过 2 MB，最多 8 张。上传后自动缩小，所有图片按统一尺寸展示。" control={
      <div className={styles.imageControls}>
        {preferences.images.length > 0 && <div className={styles.gallery} aria-label="已上传的特效图片">
          {preferences.images.map(image => <div className={styles.imageItem} key={image.id}>
            <img src={image.dataUrl} alt={image.name} />
            <Button variant="ghost" iconOnly icon={<Trash2 size={13} />} disabled={saving} aria-label={`删除特效图片 ${image.name}`}
              onClick={() => void save({ images: preferences.images.filter(item => item.id !== image.id) })}>删除</Button>
          </div>)}
        </div>}
        <Button variant="outline" disabled={saving || preferences.images.length >= MAX_EFFECT_IMAGES} icon={<ImagePlus size={14} />}
          onClick={() => uploadInput.current?.click()}>{saving ? '处理中…' : '添加图片'}</Button>
        <input ref={uploadInput} className={styles.uploadInput} type="file" accept="image/png,image/jpeg,image/webp,image/gif" aria-label="上传特效图片" disabled={saving}
          onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void upload(file); }} />
        <span className={styles.imageCount}>{preferences.images.length} / {MAX_EFFECT_IMAGES}</span>
      </div>
    } />
    <SettingsRow label="效果预览" layout="stacked" control={<div className={styles.preview}>
      <span className={styles.sample}>给码字加一点快乐<span className={styles.previewAnchor}>
        {preview > 0 && preferences.enabled && <WritingBurstParticles key={preview} preferences={preferences} seed={preview} />}
      </span></span>
      <Button variant="outline" icon={<Play size={13} />} disabled={!preferences.enabled} onClick={() => setPreview(current => current + 1)}>预览特效</Button>
    </div>} />
  </SettingsGroup>;
}
