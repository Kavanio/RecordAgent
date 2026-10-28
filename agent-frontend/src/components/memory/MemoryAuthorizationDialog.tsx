import { useRef, useState } from 'react';
import type { MemoryAuthorization, MemoryChannel, MemorySettings } from '../../types/experienceMemory';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { TextInput } from '../common/TextInput';
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
  const [retention, setRetention] = useState('7');
  const [writes, setWrites] = useState('5');
  const [queries, setQueries] = useState('30');
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
  return <ConfirmDialog open title="授权经历记忆" width={480} busy={busy} onClose={onClose} onConfirm={submit} confirmText="确认授权范围">
    <div className={styles.content}>
      <p>只处理授权生效后所选渠道的新聊天，不补录旧历史。召回会把当前问题发给记忆服务。</p>
      <p className={styles.warn}>试用期限制：远端彻底删除未核实，删除未确认时本空间召回暂停；纠正仅存本机，别填密码或敏感资料。</p>
      <div className={styles.block}>
        <span className={styles.blockTitle}>用途与来源</span>
        <label className={styles.line}><input type="checkbox" aria-label="允许保存新经历" checked={save} disabled={busy} onChange={(e) => setSave(e.target.checked)} />允许保存新经历</label>
        <label className={styles.line}><input type="checkbox" aria-label="允许查询相关经历" checked={recall} disabled={busy} onChange={(e) => setRecall(e.target.checked)} />允许查询相关经历</label>
        <div className={styles.line}>
          <span>允许渠道</span>
          {( ['DESKTOP', 'FEISHU'] as const).map((channel) => <label key={channel} className={styles.inline}>
            <input type="checkbox" checked={channels.includes(channel)} disabled={busy} onChange={(e) => chooseChannel(channel, e.target.checked)} />{channel === 'DESKTOP' ? '桌面普通聊天' : '绑定飞书普通聊天'}</label>)}
        </div>
      </div>
      <div className={styles.block}>
        <span className={styles.blockTitle}>保留与调用上限</span>
        <p className={styles.hint}>保留期只用于召回筛选，远端不会自动过期清理；每日按 UTC 计数，查询额度含状态核对，不等于费用。</p>
        <div className={styles.grid}>
          <label htmlFor="memory-retention">保留天数<TextInput id="memory-retention" type="number" min={1} max={3650} value={retention} disabled={busy} onChange={(e) => setRetention(e.target.value)} /></label>
          <label htmlFor="memory-writes">每日保存<TextInput id="memory-writes" type="number" min={1} max={1000} value={writes} disabled={busy} onChange={(e) => setWrites(e.target.value)} /></label>
          <label htmlFor="memory-queries">每日查询<TextInput id="memory-queries" type="number" min={1} max={1000} value={queries} disabled={busy} onChange={(e) => setQueries(e.target.value)} /></label>
        </div>
      </div>
      <label className={styles.line}><input type="checkbox" checked={confirmed} disabled={busy} onChange={(e) => setConfirmed(e.target.checked)} />我已确认接收方、用途、渠道、上限及试用删除限制。</label>
      {error && <p role="alert">{error}</p>}
    </div>
  </ConfirmDialog>;
}
