import { useEffect, useRef, useState } from 'react';
import {
  authorizeMemory, fetchMemorySettings, fetchMemoryStatus, revokeMemory,
  saveMemoryRuntimePolicy, saveMemorySettings,
} from '../../services/experienceMemoryService';
import type { MemoryAuthorization, MemoryRuntimePolicy, MemorySettings, MemoryStatus } from '../../types/experienceMemory';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { LeverSwitch } from '../common/LeverSwitch';
import { useMessage } from '../common/Message';
import { TextInput } from '../common/TextInput';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { MemoryAuthorizationDialog } from './MemoryAuthorizationDialog';
import { MemoryDocumentManager } from './MemoryDocumentManager';
import { MemoryConnectionSettings } from './MemoryConnectionSettings';
import styles from './ExperienceMemorySettings.module.css';

/** 接收方指纹很长，页面上只展示可辨认的头尾，避免撑满整行。 */
function shortFingerprint(value: string) {
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

const PAUSE_LABELS: Record<string, string> = {
  DELETION_UNRESOLVED: '删除未核实，召回暂停',
  NOT_AUTHORIZED: '未授权',
  SAVE_DISABLED: '保存开关关闭',
  RECALL_DISABLED: '召回开关关闭',
  WRITE_BUDGET_EXHAUSTED: '今日保存保护已用尽',
  STATUS_BUDGET_EXHAUSTED: '今日状态核对保护已用尽',
  RECALL_BUDGET_EXHAUSTED: '今日召回保护已用尽',
  LEGACY_QUERY_BUDGET_EXHAUSTED: '旧授权合并查询额度已用尽',
};
const SKIP_LABELS: Record<string, string> = {
  USER_OPT_OUT: '你要求不记住',
  SENSITIVE_OR_CODE: '敏感或代码',
  TOO_LONG: '过长',
  EMPTY: '空内容',
  UNTRUSTED_CONTEXT: '非普通聊天',
};

/** 长期模式只说明召回范围，不把“没有天数”说成永久准确或已删除。 */
function authorizationSummary(settings: MemorySettings) {
  if (!settings.authorized) return '尚无有效授权。授权只对生效后的新聊天有效，不自动开启开关。';
  const purposes = `${settings.allowSave ? '保存' : ''}${settings.allowSave && settings.allowRecall ? '、' : ''}${settings.allowRecall ? '查询' : ''}`;
  const channels = settings.channels.map(c => c === 'DESKTOP' ? '桌面' : '飞书').join('、');
  const scope = settings.policyVersion >= 2
    ? (settings.recallMode === 'ALL_AUTHORIZED' ? '长期召回（不按统一天数排除）' : `只召回最近 ${settings.retentionDays} 天`)
    : `保留 ${settings.retentionDays} 天，每日保存 ${settings.dailyWriteLimit} 次、查询 ${settings.dailyQueryLimit} 次`;
  return `已授权：${purposes}；${channels}；${scope}。`;
}

/** 积压与暂停原因来自后端本地账本，不代表远端已形成具体事实。 */
function statusSummary(status: MemoryStatus) {
  const pending = ['PENDING', 'CLAIMED', 'SUBMITTED', 'RETRY_WAIT'].reduce((sum, key) => sum + (status.operations[key] ?? 0), 0);
  const failed = status.operations.FAILED ?? 0;
  const unknown = status.operations.UNKNOWN ?? 0;
  const usage = status.usage;
  const meters = usage.legacyCombinedQueries > 0 || (usage.statusCalls === 0 && usage.recallCalls === 0)
    ? `今日合并查询 ${usage.legacyCombinedQueries}/${usage.recallLimit}`
    : `今日保存 ${usage.writeCalls}/${usage.writeLimit}、状态核对 ${usage.statusCalls}/${usage.statusLimit}、召回 ${usage.recallCalls}/${usage.recallLimit}`;
  const skipped = Object.entries(status.skippedToday)
    .map(([reason, count]) => `${SKIP_LABELS[reason] ?? reason} ${count}`).join('、');
  const pauses = status.pauseReasons.map(reason => PAUSE_LABELS[reason] ?? reason).join('、');
  return `来源 ${status.sourceActive}/${status.sourceTotal} 仍有效；待处理 ${pending}、失败 ${failed}、结果未知 ${unknown}；${meters}。`
    + `${pauses ? `当前暂停：${pauses}。` : ''}${skipped ? `今日过滤：${skipped}。` : ''}`;
}

/** 经历记忆独立设置：保存设置、用途授权、运行保护和来源管理各自保持权威状态。 */
export function ExperienceMemorySettings() {  const { showMessage } = useMessage();
  const [settings, setSettings] = useState<MemorySettings | null>(null);
  const [status, setStatus] = useState<MemoryStatus | null>(null);
  const [error, setError] = useState('');
  const [statusError, setStatusError] = useState('');
  const [busy, setBusy] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const [editingRuntime, setEditingRuntime] = useState(false);
  const [runtimeDraft, setRuntimeDraft] = useState({ batchSize: '', write: '', status: '', recall: '' });
  const revokeRequest = useRef<{ revision: number; id: string } | null>(null);
  const loadStatus = async () => {
    setStatusError('');
    try { setStatus(await fetchMemoryStatus()); }
    catch { setStatusError('处理状态读取失败，可稍后重试；这不影响已有来源。'); }
  };
  const load = async () => {
    setError(''); setBusy(true);
    try { setSettings(await fetchMemorySettings()); await loadStatus(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : '记忆设置读取失败'); }
    finally { setBusy(false); }
  };
  useEffect(() => { void load(); }, []);
  const mutate = async (action: () => Promise<void>, message: string) => {
    setBusy(true);
    try { await action(); showMessage('success', message); }
    catch (cause) { showMessage('error', cause instanceof Error ? cause.message : '记忆设置操作失败，请刷新后重试'); }
    finally { setBusy(false); }
  };
  const toggle = (save: boolean, recall: boolean) => {
    if (settings) void mutate(async () => setSettings(await saveMemorySettings(settings, save, recall)), '记忆开关已保存');
  };
  const authorize = async (input: MemoryAuthorization) => mutate(async () => {
    await authorizeMemory(input);
    setSettings(await fetchMemorySettings());
    setAuthorizing(false);
    await loadStatus();
  }, '用途已授权，保存和召回开关保持原设置');
  const revoke = async () => {
    if (!settings) return;
    if (revokeRequest.current?.revision !== settings.authorizationRevision)
      revokeRequest.current = { revision: settings.authorizationRevision, id: crypto.randomUUID() };
    const request = revokeRequest.current;
    await mutate(async () => {
      await revokeMemory(request.revision, request.id);
      setSettings(await fetchMemorySettings());
      setRevoking(false);
    }, '授权已撤销，保存与召回已关闭；远端数据仍须单独删除');
  };
  const openRuntime = () => {
    if (!settings) return;
    const policy = settings.runtimePolicy;
    setRuntimeDraft({ batchSize: String(policy.batchSize), write: String(policy.dailyWriteLimit),
      status: String(policy.dailyStatusLimit), recall: String(policy.dailyRecallLimit) });
    setEditingRuntime(true);
  };
  const saveRuntime = async () => {
    if (!settings) return;
    const policy = settings.runtimePolicy;
    const numbers = { batchSize: Number(runtimeDraft.batchSize), write: Number(runtimeDraft.write),
      status: Number(runtimeDraft.status), recall: Number(runtimeDraft.recall) };
    const valid = (value: number, min: number, max: number) => Number.isInteger(value) && value >= min && value <= max;
    if (!valid(numbers.batchSize, 1, 20) || !valid(numbers.write, 1, 100000)
      || !valid(numbers.status, 1, 100000) || !valid(numbers.recall, 1, 100000)) {
      showMessage('error', '请填写有效范围：每批 1—20，每日保护 1—100000。');
      return;
    }
    await mutate(async () => {
      const updated: MemoryRuntimePolicy = await saveMemoryRuntimePolicy({
        version: policy.version, batchSize: numbers.batchSize, dailyWriteLimit: numbers.write,
        dailyStatusLimit: numbers.status, dailyRecallLimit: numbers.recall,
      });
      setSettings({ ...settings, runtimePolicy: updated });
      setEditingRuntime(false);
      await loadStatus();
    }, '运行保护已保存，授权与已有来源不变');
  };
  return <div className={styles.content}>
    <MemoryConnectionSettings onChanged={load} />
    <SettingsGroup title="经历记忆" description="从获准的新普通聊天持续积累经历，并为当前问题召回少量相关片段。默认关闭。">
      <SettingsRow label="远端接入" description={settings?.remoteAvailable
        ? <>接收方 <code className={styles.fingerprint}>{shortFingerprint(settings.recipientFingerprint)}</code>；还须授权并开启对应开关，不代表完整远端删除已核实。</>
        : '当前未接入远端记忆服务，不会上传聊天；请先配置上方 Hindsight 试用连接。'}
        control={<span className={styles.badge}>{settings ? settings.remoteAvailable ? '已接入' : '未接入' : '读取中…'}</span>} />
      {error && <SettingsRow label="设置读取失败" description={<span role="alert">{error}</span>} control={<Button variant="outline" disabled={busy} onClick={() => void load()}>重试读取设置</Button>} />}
      {settings && <>
        <SettingsRow label="用途授权" description={authorizationSummary(settings)}
          control={<div className={styles.actions}><Button variant="outline" disabled={busy || !settings.remoteAvailable} onClick={() => setAuthorizing(true)}>{settings.authorized ? '重新授权' : '查看并授权'}</Button>
            <Button variant="ghost" disabled={busy || !settings.authorized} onClick={() => setRevoking(true)}>撤销授权</Button></div>} />
        <SettingsRow label="自动保存新经历" description="仅处理授权后的普通用户文本；明确说不要记住的当轮不上传，暂停不删除既有数据。" control={<LeverSwitch aria-label="自动保存新经历" checked={settings.autoSave} disabled={busy || !settings.authorized || !settings.allowSave} label={settings.autoSave ? '已开启' : '已关闭'} onChange={(value) => toggle(value, settings.autoRecall)} />} />
        <SettingsRow label="自动召回经历" description="还需上方“自动使用个人上下文”开启；查询与保存独立控制。" control={<LeverSwitch aria-label="自动召回经历" checked={settings.autoRecall} disabled={busy || !settings.authorized || !settings.allowRecall} label={settings.autoRecall ? '已开启' : '已关闭'} onChange={(value) => toggle(settings.autoSave, value)} />} />
        <SettingsRow label="运行保护" description={`每批 ${settings.runtimePolicy.batchSize} 条；每日保护：保存 ${settings.runtimePolicy.dailyWriteLimit}、状态核对 ${settings.runtimePolicy.dailyStatusLimit}、召回 ${settings.runtimePolicy.dailyRecallLimit}。调整不影响授权与已积累来源，也不等于费用封顶。`}
          control={<Button variant="outline" disabled={busy} onClick={openRuntime}>调整运行保护</Button>} />
        <SettingsRow label="处理状态" description={status ? statusSummary(status) : (statusError || '正在读取积压与暂停原因…')}
          control={<Button variant="ghost" disabled={busy} onClick={() => void loadStatus()}>刷新处理状态</Button>} />
        <SettingsRow label="刷新权威状态" description="其他窗口更改或操作冲突后，可重新读取。" control={<Button variant="ghost" disabled={busy} onClick={() => void load()}>刷新记忆设置</Button>} />
      </>}
    </SettingsGroup>
    <MemoryDocumentManager />
    {authorizing && settings && <MemoryAuthorizationDialog settings={settings} busy={busy} onClose={() => setAuthorizing(false)} onConfirm={authorize} />}
    <ConfirmDialog open={editingRuntime} title="调整运行保护" busy={busy} confirmText="保存运行保护" onClose={() => setEditingRuntime(false)} onConfirm={saveRuntime}
      description="只调节后台节流与每日调用保护，不改变用途、渠道、召回范围，也不会使用已有来源失效。达到保护上限时任务保留待办，不会静默丢弃。">
      <div className={styles.runtimeGrid}>
        <label htmlFor="memory-runtime-batch">每批条数<TextInput id="memory-runtime-batch" type="number" min={1} max={20} value={runtimeDraft.batchSize} disabled={busy} onChange={(e) => setRuntimeDraft({ ...runtimeDraft, batchSize: e.target.value })} /></label>
        <label htmlFor="memory-runtime-write">每日保存保护<TextInput id="memory-runtime-write" type="number" min={1} max={100000} value={runtimeDraft.write} disabled={busy} onChange={(e) => setRuntimeDraft({ ...runtimeDraft, write: e.target.value })} /></label>
        <label htmlFor="memory-runtime-status">每日状态核对保护<TextInput id="memory-runtime-status" type="number" min={1} max={100000} value={runtimeDraft.status} disabled={busy} onChange={(e) => setRuntimeDraft({ ...runtimeDraft, status: e.target.value })} /></label>
        <label htmlFor="memory-runtime-recall">每日召回保护<TextInput id="memory-runtime-recall" type="number" min={1} max={100000} value={runtimeDraft.recall} disabled={busy} onChange={(e) => setRuntimeDraft({ ...runtimeDraft, recall: e.target.value })} /></label>
      </div>
    </ConfirmDialog>
    <ConfirmDialog open={revoking} title="撤销经历记忆授权" tone="danger" busy={busy} confirmText="确认撤销" onClose={() => setRevoking(false)} onConfirm={revoke}
      description="立即停用旧来源，关闭保存和召回。此操作不删除已提交的远端数据；你仍可在来源管理中发起删除并核对结果。" />
  </div>;
}
