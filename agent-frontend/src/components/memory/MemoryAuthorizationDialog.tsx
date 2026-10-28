import { useRef, useState } from 'react';
import type { MemoryAuthorization, MemoryChannel, MemorySettings } from '../../types/experienceMemory';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { TextInput } from '../common/TextInput';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import styles from './MemoryAuthorizationDialog.module.css';

interface Props {
  settings: MemorySettings;
  busy: boolean;
  onClose: () => void;
  onConfirm: (input: MemoryAuthorization) => Promise<void>;
}

/** 每次打开创建独立授权草稿；用途、渠道和额度逐项确认后才提交。 */
export function MemoryAuthorizationDialog({ settings, busy, onClose, onConfirm }: Props) {
  const [save, setSave] = useState(false);
  const [recall, setRecall] = useState(false);
  const [channels, setChannels] = useState<MemoryChannel[]>([]);
  const [retention, setRetention] = useState('');
  const [writes, setWrites] = useState('');
  const [queries, setQueries] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState('');
  const command = useRef<{ key: string; id: string } | null>(null);
  const chooseChannel = (channel: MemoryChannel, enabled: boolean) =>
    setChannels((current) => enabled ? [...current, channel] : current.filter((item) => item !== channel));
  const submit = async () => {
    const validNumber = (value: string, max: number) => /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= max;
    if ((!save && !recall) || !channels.length || !confirmed || !validNumber(retention, 3650)
      || !validNumber(writes, 1000) || !validNumber(queries, 1000)) {
      setError('请选择用途和渠道，填写保留天数（1—3650）、每日上限（1—1000），并勾选确认。');
      return;
    }
    const key = JSON.stringify({ revision: settings.authorizationRevision, recipient: settings.recipientFingerprint,
      save, recall, channels, retention, writes, queries });
    if (command.current?.key !== key) command.current = { key, id: crypto.randomUUID() };
    await onConfirm({ expectedRevision: settings.authorizationRevision, recipientFingerprint: settings.recipientFingerprint,
      allowSave: save, allowRecall: recall, channels, retentionDays: Number(retention), dailyWriteLimit: Number(writes),
      dailyQueryLimit: Number(queries), confirmed, requestId: command.current.id });
  };
  return <ConfirmDialog open title="授权经历记忆" width={620} busy={busy} onClose={onClose} onConfirm={submit} confirmText="确认授权范围">
    <div className={styles.content}>
      <p>接收方标识：<code>{settings.recipientFingerprint}</code></p>
      <p>授权生效后，仅处理所选渠道的新普通聊天文本；不补录旧历史。外部服务可能抽取相关经历，召回时会收到当前问题的有界文本。重新授权会停用旧来源；已开启的开关按新范围继续运行，关闭的开关保持关闭。撤权不会删除既有远端数据。</p>
      <SettingsGroup title="用途与来源">
        <SettingsRow label="允许保存新经历" control={<input type="checkbox" aria-label="允许保存新经历" checked={save} disabled={busy} onChange={(e) => setSave(e.target.checked)} />} />
        <SettingsRow label="允许查询相关经历" control={<input type="checkbox" aria-label="允许查询相关经历" checked={recall} disabled={busy} onChange={(e) => setRecall(e.target.checked)} />} />
        <SettingsRow label="允许渠道" control={<div className={styles.options}>{(['DESKTOP', 'FEISHU'] as const).map((channel) => <label key={channel}><input type="checkbox" checked={channels.includes(channel)} disabled={busy} onChange={(e) => chooseChannel(channel, e.target.checked)} />{channel === 'DESKTOP' ? '桌面普通聊天' : '绑定飞书普通聊天'}</label>)}</div>} />
      </SettingsGroup>
      <SettingsGroup title="保留与调用上限" description="保留期用于召回筛选，远端自动到期清理尚未开放；每日调用按 UTC 计数，不等于费用或 Token。">
        <SettingsRow label="保留天数" labelFor="memory-retention" control={<TextInput id="memory-retention" type="number" min={1} max={3650} value={retention} disabled={busy} onChange={(e) => setRetention(e.target.value)} />} />
        <SettingsRow label="每日保存上限" labelFor="memory-writes" control={<TextInput id="memory-writes" type="number" min={1} max={1000} value={writes} disabled={busy} onChange={(e) => setWrites(e.target.value)} />} />
        <SettingsRow label="每日查询上限" labelFor="memory-queries" control={<TextInput id="memory-queries" type="number" min={1} max={1000} value={queries} disabled={busy} onChange={(e) => setQueries(e.target.value)} />} />
      </SettingsGroup>
      <label className={styles.options}><input type="checkbox" checked={confirmed} disabled={busy} onChange={(e) => setConfirmed(e.target.checked)} />我已确认接收方、用途、渠道和上限，理解已有开关将在新授权范围内继续生效。</label>
      {error && <p role="alert">{error}</p>}
    </div>
  </ConfirmDialog>;
}
