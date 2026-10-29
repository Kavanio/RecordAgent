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
 * 每次打开创建独立授权草稿；用途、渠道和召回范围逐项确认后才提交。
 *
 * <p>长期模式不再让用户填写每日记忆条数：持续后台处理是明确的用途确认，
 * 日常节流在“运行保护”里单独调整，调频不会使已积累来源失效。</p>
 */
export function MemoryAuthorizationDialog({ settings, busy, onClose, onConfirm }: Props) {
  const [save, setSave] = useState(false);
  const [recall, setRecall] = useState(false);
  const [channels, setChannels] = useState<MemoryChannel[]>([]);
  const [mode, setMode] = useState<MemoryRecallMode>('ALL_AUTHORIZED');
  const [retention, setRetention] = useState('90');
  const [continuous, setContinuous] = useState(false);
  const [impact, setImpact] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState('');
  const command = useRef<{ key: string; id: string } | null>(null);
  const chooseChannel = (channel: MemoryChannel, enabled: boolean) =>
    setChannels((current) => enabled ? [...current, channel] : current.filter((item) => item !== channel));
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
    if (!continuous || !impact || !confirmed) {
      setError('请确认持续后台处理、已有来源影响和接收方风险。');
      return;
    }
    const key = JSON.stringify({ revision: settings.authorizationRevision, recipient: settings.recipientFingerprint,
      save, recall, channels, mode, retention, continuous, impact });
    if (command.current?.key !== key) command.current = { key, id: crypto.randomUUID() };
    await onConfirm({ expectedRevision: settings.authorizationRevision, recipientFingerprint: settings.recipientFingerprint,
      allowSave: save, allowRecall: recall, channels, policyVersion: 2, recallMode: mode,
      retentionDays: mode === 'RECENT_DAYS' ? days : null, continuousProcessing: continuous,
      acknowledgeSourceImpact: impact, confirmed, requestId: command.current.id });
  };
  return <ConfirmDialog open title="授权经历记忆" width={480} busy={busy} onClose={onClose} onConfirm={submit} confirmText="确认授权范围">
    <div className={styles.content}>
      <p>只处理授权生效后所选渠道的新普通聊天，不补录旧历史。召回会把当前问题发给记忆服务。</p>
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
        <span className={styles.blockTitle}>召回范围</span>
        <label className={styles.line}><input type="radio" name="memory-recall-mode" aria-label="长期召回获准来源" checked={mode === 'ALL_AUTHORIZED'} disabled={busy} onChange={() => setMode('ALL_AUTHORIZED')} />长期：不按统一天数排除获准来源</label>
        <label className={styles.line}><input type="radio" name="memory-recall-mode" aria-label="只召回最近一段时间" checked={mode === 'RECENT_DAYS'} disabled={busy} onChange={() => setMode('RECENT_DAYS')} />只召回最近一段时间</label>
        {mode === 'RECENT_DAYS' && <div className={styles.grid}>
          <label htmlFor="memory-retention">召回天数<TextInput id="memory-retention" type="number" min={1} max={3650} value={retention} disabled={busy} onChange={(e) => setRetention(e.target.value)} /></label>
        </div>}
        <p className={styles.hint}>长期只影响“能不能被检索”，不代表内容永远正确，也不代表远端到期删除；旧偏好是否仍成立由回复结合时间判断。</p>
      </div>
      <div className={styles.block}>
        <span className={styles.blockTitle}>持续处理与影响</span>
        <label className={styles.line}><input type="checkbox" aria-label="允许持续在后台处理新聊天" checked={continuous} disabled={busy} onChange={(e) => setContinuous(e.target.checked)} />允许后台持续处理获准的新聊天（不设产品日记忆条数上限）</label>
        <label className={styles.line}><input type="checkbox" aria-label="确认已有来源不会自动沿用" checked={impact} disabled={busy} onChange={(e) => setImpact(e.target.checked)} />我理解：确认后已有来源不能自动沿用，需要从新的聊天重新积累</label>
        <p className={styles.hint}>运行频率、每日调用保护在“运行保护”中单独调整，不影响本次授权；调用次数不等于费用封顶。</p>
      </div>
      <label className={styles.line}><input type="checkbox" checked={confirmed} disabled={busy} onChange={(e) => setConfirmed(e.target.checked)} />我已确认接收方、用途、渠道、召回范围及试用删除限制。</label>
      {error && <p role="alert">{error}</p>}
    </div>
  </ConfirmDialog>;
}
