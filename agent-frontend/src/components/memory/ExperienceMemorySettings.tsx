import { useEffect, useRef, useState } from 'react';
import { authorizeMemory, fetchMemorySettings, revokeMemory, saveMemorySettings } from '../../services/experienceMemoryService';
import type { MemoryAuthorization, MemorySettings } from '../../types/experienceMemory';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { LeverSwitch } from '../common/LeverSwitch';
import { useMessage } from '../common/Message';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { MemoryAuthorizationDialog } from './MemoryAuthorizationDialog';
import { MemoryDocumentManager } from './MemoryDocumentManager';
import { MemoryConnectionSettings } from './MemoryConnectionSettings';
import styles from './ExperienceMemorySettings.module.css';

/** 经历记忆独立设置：保存设置、用途授权和来源管理各自保持权威状态。 */
export function ExperienceMemorySettings() {
  const { showMessage } = useMessage();
  const [settings, setSettings] = useState<MemorySettings | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [authorizing, setAuthorizing] = useState(false);
  const [revoking, setRevoking] = useState(false);
  const revokeRequest = useRef<{ revision: number; id: string } | null>(null);
  const load = async () => {
    setError(''); setBusy(true);
    try { setSettings(await fetchMemorySettings()); }
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
  return <div className={styles.content}>
    <MemoryConnectionSettings onChanged={load} />
    <SettingsGroup title="经历记忆" description="从获准的新普通聊天保存经历，并为当前问题召回少量相关片段。默认关闭。">
      <SettingsRow label="远端接入" description={settings?.remoteAvailable ? '试用接收方已由后端配置；还须授权并开启对应开关，不代表完整远端删除已核实。' : '当前未接入远端记忆服务，不会上传聊天；请先配置上方 Hindsight 试用连接。'}
        control={<span>{settings ? settings.remoteAvailable ? settings.recipientFingerprint : '未接入' : '正在读取…'}</span>} />
      {error && <SettingsRow label="设置读取失败" description={<span role="alert">{error}</span>} control={<Button variant="outline" disabled={busy} onClick={() => void load()}>重试读取设置</Button>} />}
      {settings && <>
        <SettingsRow label="用途授权" description={settings.authorized
          ? `已授权：${settings.allowSave ? '保存' : ''}${settings.allowSave && settings.allowRecall ? '、' : ''}${settings.allowRecall ? '查询' : ''}；${settings.channels.map(c => c === 'DESKTOP' ? '桌面' : '飞书').join('、')}；保留 ${settings.retentionDays} 天，每日保存 ${settings.dailyWriteLimit} 次、查询 ${settings.dailyQueryLimit} 次。`
          : '尚无有效授权。授权只对生效后的新聊天有效，不自动开启开关。'}
          control={<div className={styles.actions}><Button variant="outline" disabled={busy || !settings.remoteAvailable} onClick={() => setAuthorizing(true)}>{settings.authorized ? '重新授权' : '查看并授权'}</Button>
            <Button variant="ghost" disabled={busy || !settings.authorized} onClick={() => setRevoking(true)}>撤销授权</Button></div>} />
        <SettingsRow label="自动保存新经历" description="仅处理授权后的普通用户文本，暂停不删除既有数据。" control={<LeverSwitch aria-label="自动保存新经历" checked={settings.autoSave} disabled={busy || !settings.authorized || !settings.allowSave} label={settings.autoSave ? '已开启' : '已关闭'} onChange={(value) => toggle(value, settings.autoRecall)} />} />
        <SettingsRow label="自动召回经历" description="还需上方“自动使用个人上下文”开启；查询与保存独立控制。" control={<LeverSwitch aria-label="自动召回经历" checked={settings.autoRecall} disabled={busy || !settings.authorized || !settings.allowRecall} label={settings.autoRecall ? '已开启' : '已关闭'} onChange={(value) => toggle(settings.autoSave, value)} />} />
        <SettingsRow label="刷新权威状态" description="其他窗口更改或操作冲突后，可重新读取。" control={<Button variant="ghost" disabled={busy} onClick={() => void load()}>刷新记忆设置</Button>} />
      </>}
    </SettingsGroup>
    <MemoryDocumentManager />
    {authorizing && settings && <MemoryAuthorizationDialog settings={settings} busy={busy} onClose={() => setAuthorizing(false)} onConfirm={authorize} />}
    <ConfirmDialog open={revoking} title="撤销经历记忆授权" tone="danger" busy={busy} confirmText="确认撤销" onClose={() => setRevoking(false)} onConfirm={revoke}
      description="立即停用旧来源，关闭保存和召回。此操作不删除已提交的远端数据；你仍可在来源管理中发起删除并核对结果。" />
  </div>;
}
