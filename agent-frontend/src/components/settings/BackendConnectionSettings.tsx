import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { SettingsGroup, SettingsRow } from './SettingsGroup';
import { SettingsPageLayout } from './SettingsPageLayout';
import { TextInput } from '../common/TextInput';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { testBackendConnection, type BackendConnectionInfo } from '../../services/api';
import styles from './BackendConnectionSettings.module.css';

/** 桌面端后端连接入口；保存后重启切换，避免本地与远程调度器并行运行。 */
export function BackendConnectionSettings() {
  const [connection, setConnection] = useState<BackendConnectionInfo | null>(null);
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const { showMessage } = useMessage();

  useEffect(() => {
    void invoke<BackendConnectionInfo>('get_backend_connection').then((value) => {
      setConnection(value);
      setUrl(value.remoteUrl ?? '');
    }).catch((error: unknown) => showMessage('error', error instanceof Error ? error.message : '读取后端设置失败'));
  }, [showMessage]);

  const test = async () => {
    setBusy(true);
    try {
      await testBackendConnection(url);
      showMessage('success', '后端连接成功');
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '无法连接后端');
    } finally {
      setBusy(false);
    }
  };

  const save = async (remoteUrl: string | null) => {
    setBusy(true);
    try {
      await invoke('save_remote_backend_url', { remoteUrl });
      showMessage('success', remoteUrl ? '远程地址已保存，请重启应用切换' : '已保存本地后端模式，请重启应用切换');
      setUrl(remoteUrl ?? '');
      setConnection((current) => current ? { ...current, remoteUrl } : current);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '保存后端设置失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <SettingsPageLayout title="后端连接" description="选择由本机或工作室服务器运行 Agent 与自动任务。">
      <SettingsGroup title="运行位置" description="切换在重启应用后生效。远程模式不会启动本地 sidecar，确保只有服务器执行日报和邮件任务。">
        <SettingsRow
          label="当前模式"
          description={connection?.mode === 'remote' ? '远程服务器负责 Agent、数据与自动任务；本机保留桌面提醒和使用状态采样。' : connection?.mode === 'sidecar' ? '应用在本机启动独立后端。' : '开发模式连接本机 8081 端口。'}
          control={<span className={styles.mode}>{connection?.mode === 'remote' ? '远程服务器' : connection?.mode === 'sidecar' ? '本机打包后端' : '本机开发后端'}</span>}
        />
        <SettingsRow
          label="服务器 API 地址"
          description="例如 http://工作室服务器局域网地址:8081。服务器需要允许桌面端来源跨域访问。"
          labelFor="remote-backend-url"
          control={<TextInput id="remote-backend-url" value={url} onChange={(event) => setUrl(event.target.value)} placeholder="http://192.168.1.10:8081" autoComplete="url" />}
        />
        <div className={styles.actions}>
          <Button variant="outline" onClick={() => void test()} disabled={busy || !url.trim()}>测试连接</Button>
          <Button variant="primary" onClick={() => void save(url.trim())} disabled={busy || !url.trim()}>保存远程地址</Button>
          {connection?.remoteUrl && <Button variant="ghost" onClick={() => void save(null)} disabled={busy}>切回本机</Button>}
        </div>
      </SettingsGroup>
      <SettingsGroup title="设备提醒说明">
        <p className={styles.note}>日报、周报、月报和邮件由服务器持续执行。桌面通知、休息遮罩和久坐采样由本机 App 处理；电脑关机或 App 完全退出时，本机采样与提醒会暂停。未确认的普通桌面通知超过 15 分钟后会过期跳过，待确认提醒会保留到你处理。</p>
      </SettingsGroup>
      <SettingsGroup title="远程访问安全">
        <p className={styles.note}>当前后端仍是单用户服务，尚未提供 API 登录认证。仅在你能控制的可信局域网内使用，并将服务器防火墙限制为你的设备；不要把 8081 端口开放到公网。服务器端认证与部署流程完成后再用于不可信网络。</p>
      </SettingsGroup>
    </SettingsPageLayout>
  );
}
