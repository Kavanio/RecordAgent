import { useEffect, useState } from 'react';
import { SettingsGroup, SettingsRow } from './SettingsGroup';
import { SettingsPageLayout } from './SettingsPageLayout';
import { BackendConnectionModal } from './BackendConnectionModal';
import { Button } from '../common/Button';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { useMessage } from '../common/Message';
import { fetchBackendConnection, restartApplication, type BackendConnectionInfo } from '../../services/api';
import styles from './BackendConnectionSettings.module.css';

/** 区分本次实际连接与下次启动配置，保存变更后由用户确认自动重启。 */
export function BackendConnectionSettings() {
  const [connection, setConnection] = useState<BackendConnectionInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [editing, setEditing] = useState(false);
  const [restartPrompt, setRestartPrompt] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const { showMessage } = useMessage();

  useEffect(() => {
    let active = true;
    void fetchBackendConnection().then((value) => {
      if (active) setConnection(value);
    }).catch((error: unknown) => {
      if (!active) return;
      setFailed(true);
      showMessage('error', error instanceof Error ? error.message : typeof error === 'string' ? error : '读取后端设置失败');
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [showMessage]);

  const restart = async () => {
    setRestarting(true);
    try {
      await restartApplication();
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '自动重启失败，请退出应用后重新打开');
    } finally {
      setRestarting(false);
    }
  };

  return (
    <SettingsPageLayout title="后端连接" description="选择由本机或工作室服务器运行 Agent 与自动任务。" loading={loading}>
      <SettingsGroup title="运行位置" description="切换在重启应用后生效。远程不可用时，本次启动会自动回退到本机后端，重启后重新尝试远程连接。">
        <SettingsRow
          label="当前连接"
          description={connection?.mode === 'remote' ? `当前连接：${connection.remoteUrl}。远程服务器负责 Agent、数据与自动任务，本机保留桌面提醒和使用状态采样。` : connection?.remoteUrl ? '远程服务器启动时不可达，本次已自动切换到本机数据。' : connection?.mode === 'sidecar' ? '应用在本机启动独立后端。' : connection ? '开发模式连接本机 8081 端口。' : failed ? '读取失败，请重新进入此页面重试。' : '正在读取连接状态。'}
          control={<span className={styles.mode}>{connection?.mode === 'remote' ? '远程服务器' : connection?.remoteUrl ? '本机后端（自动降级）' : connection ? '本机后端' : '尚未读取'}</span>}
        />
        <SettingsRow
          label="连接配置"
          description={connection ? `${connection.savedRemoteUrl ? `已保存：${connection.savedRemoteUrl}` : '已保存：本机后端'}${connection.restartRequired ? '；等待重启生效，当前连接尚未切换。' : '。点击配置可修改运行位置、服务器地址和 API Token。'}` : '配置保存到当前设备。'}
          control={<div className={styles.actions}>
            {connection?.restartRequired && <Button variant="outline" onClick={() => setRestartPrompt(true)}>立即重启</Button>}
            <Button variant="outline" disabled={!connection} onClick={() => setEditing(true)}>配置连接</Button>
          </div>}
        />
      </SettingsGroup>
      <SettingsGroup title="设备提醒说明">
        <p className={styles.note}>日报、周报、月报和邮件由当前连接的后端执行，连接远程时由服务器持续执行。桌面通知、休息遮罩和久坐采样由本机 App 处理；电脑关机或 App 完全退出时，本机采样与提醒会暂停。未确认的普通桌面通知超过 15 分钟后会过期跳过，待确认提醒会保留到你处理。</p>
      </SettingsGroup>
      <SettingsGroup title="远程访问安全">
        <p className={styles.note}>服务器必须开启令牌校验（BUTVAN_API_REQUIRE_TOKEN=true），并限制防火墙只允许你的设备访问。当前业务仍是单用户服务；请通过 HTTPS 或可信 VPN 连接，不要把 8081 端口开放到公网。</p>
      </SettingsGroup>
      {editing && connection && <BackendConnectionModal connection={connection} onClose={() => setEditing(false)} onSaved={(changed, remoteUrl) => {
        setConnection((current) => current ? { ...current, savedRemoteUrl: remoteUrl, hasApiToken: Boolean(remoteUrl), restartRequired: current.restartRequired || changed } : current);
        setEditing(false);
        if (changed) setRestartPrompt(true);
      }} />}
      <ConfirmDialog open={restartPrompt} title="重启应用以切换后端" tone="info" description="连接配置已保存，重启后生效。请先结束正在进行的对话并保存未完成的编辑；点击立即重启后，应用将自动退出并重新打开。" cancelText="稍后重启" confirmText="立即重启" busy={restarting} onClose={() => setRestartPrompt(false)} onConfirm={restart} />
    </SettingsPageLayout>
  );
}
