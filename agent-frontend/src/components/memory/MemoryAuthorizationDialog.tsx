import { useRef, useState } from 'react';
import type { MemoryAuthorization, MemoryChannel, MemoryRecallMode, MemorySettings } from '../../types/experienceMemory';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { TextInput } from '../common/TextInput';
import styles from './MemoryAuthorizationDialog.module.css';

interface Props {
  settings: MemorySettings;
  busy: boolean;
  onClose: () => void;
  onConfirm: (input: MemoryAuthorization) => Promise<void>;
}

/**
 * 经历记忆用途与召回范围授权弹窗：
 * 首次打开默认勾选保存、查询与全部渠道，点击“确认授权范围”即可一步完成授权。
 */
export function MemoryAuthorizationDialog({ settings, busy, onClose, onConfirm }: Props) {
  const [save, setSave] = useState(settings.authorized ? settings.allowSave : true);
  const [recall, setRecall] = useState(settings.authorized ? settings.allowRecall : true);
  const [channels, setChannels] = useState<MemoryChannel[]>(() =>
    settings.authorized ? [...settings.channels] : ['DESKTOP', 'FEISHU'],
  );
  const [mode, setMode] = useState<MemoryRecallMode>(
    settings.authorized ? (settings.policyVersion >= 2 ? settings.recallMode : 'RECENT_DAYS') : 'ALL_AUTHORIZED',
  );
  const [retention, setRetention] = useState(
    settings.authorized && settings.retentionDays > 0 ? String(settings.retentionDays) : '90',
  );
  const [error, setError] = useState('');
  const command = useRef<{ key: string; id: string } | null>(null);

  const chooseChannel = (channel: MemoryChannel, enabled: boolean) =>
    setChannels((current) => (enabled ? [...current, channel] : current.filter((item) => item !== channel)));

  const submit = async () => {
    const days = Number(retention);
    if ((!save && !recall) || !channels.length) {
      setError('请选择用途和允许渠道。');
      return;
    }
    if (mode === 'RECENT_DAYS' && !(/^\d+$/.test(retention) && days >= 1 && days <= 3650)) {
      setError('“最近一段时间”需填写 1—3650 天；需要长期召回请选择长期模式。');
      return;
    }
    const key = JSON.stringify({
      revision: settings.authorizationRevision,
      recipient: settings.recipientFingerprint,
      save,
      recall,
      channels,
      mode,
      retention,
    });
    if (command.current?.key !== key) command.current = { key, id: crypto.randomUUID() };
    await onConfirm({
      expectedRevision: settings.authorizationRevision,
      recipientFingerprint: settings.recipientFingerprint,
      allowSave: save,
      allowRecall: recall,
      channels,
      policyVersion: 2,
      recallMode: mode,
      retentionDays: mode === 'RECENT_DAYS' ? days : null,
      continuousProcessing: true,
      acknowledgeSourceImpact: true,
      confirmed: true,
      requestId: command.current.id,
    });
  };

  return (
    <ConfirmDialog open title="授权经历记忆" width={480} busy={busy} onClose={onClose} onConfirm={submit} confirmText="确认授权范围">
      <div className={styles.content}>
        <p>只处理授权生效后所选渠道的新普通聊天，不补录旧历史。</p>
        <div className={styles.block}>
          <span className={styles.blockTitle}>用途与来源</span>
          <label className={styles.line}>
            <input type="checkbox" aria-label="允许保存新经历" checked={save} disabled={busy} onChange={(e) => setSave(e.target.checked)} />
            允许保存新经历
          </label>
          <label className={styles.line}>
            <input type="checkbox" aria-label="允许查询相关经历" checked={recall} disabled={busy} onChange={(e) => setRecall(e.target.checked)} />
            允许查询相关经历
          </label>
          <div className={styles.line}>
            <span>允许渠道</span>
            {(['DESKTOP', 'FEISHU'] as const).map((channel) => (
              <label key={channel} className={styles.inline}>
                <input
                  type="checkbox"
                  checked={channels.includes(channel)}
                  disabled={busy}
                  onChange={(e) => chooseChannel(channel, e.target.checked)}
                />
                {channel === 'DESKTOP' ? '桌面普通聊天' : '绑定飞书普通聊天'}
              </label>
            ))}
          </div>
        </div>
        <div className={styles.block}>
          <span className={styles.blockTitle}>召回范围</span>
          <label className={styles.line}>
            <input
              type="radio"
              name="memory-recall-mode"
              aria-label="长期召回获准来源"
              checked={mode === 'ALL_AUTHORIZED'}
              disabled={busy}
              onChange={() => setMode('ALL_AUTHORIZED')}
            />
            长期：不按统一天数排除获准来源
          </label>
          <label className={styles.line}>
            <input
              type="radio"
              name="memory-recall-mode"
              aria-label="只召回最近一段时间"
              checked={mode === 'RECENT_DAYS'}
              disabled={busy}
              onChange={() => setMode('RECENT_DAYS')}
            />
            只召回最近一段时间
          </label>
          {mode === 'RECENT_DAYS' && (
            <div className={styles.grid}>
              <label htmlFor="memory-retention">
                召回天数
                <TextInput
                  id="memory-retention"
                  type="number"
                  min={1}
                  max={3650}
                  value={retention}
                  disabled={busy}
                  onChange={(e) => setRetention(e.target.value)}
                />
              </label>
            </div>
          )}
        </div>
        {error && <p role="alert">{error}</p>}
      </div>
    </ConfirmDialog>
  );
}
