import { useEffect, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { SettingsGroup, SettingsRow } from './SettingsGroup';
import { SettingsPageLayout } from './SettingsPageLayout';
import { TextInput } from '../common/TextInput';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { saveBackendConnection, testBackendConnection, type BackendConnectionInfo } from '../../services/api';
import styles from './BackendConnectionSettings.module.css';

/** 桌面端后端连接入口；保存后重启切换，避免本地与远程调度器并行运行。 */
export function BackendConnectionSettings() {
  const [connection, setConnection] = useState<BackendConnectionInfo | null>(null);
  const [url, setUrl] = useState('');
  const [token, setToken] = useState('');
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
      const savedToken = token.trim() || await invoke<string | null>('get_backend_api_token');
      await testBackendConnection(url, savedToken);
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
      await saveBackendConnection(remoteUrl, remoteUrl ? token : null);
      showMessage('success', remoteUrl ? '远程地址已保存，请重启应用切换' : '已保存本地后端模式，请重启应用切换');
      setUrl(remoteUrl ?? '');
      setToken('');
      setConnection((current) => current ? { ...current, remoteUrl, hasApiToken: Boolean(remoteUrl) } : current);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : '保存后端设置失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <SettingsPageLayout title="后端连接" description="选择由本机或工作室服务器运行 Agent 与自动任务。">
      <SettingsGroup title="运行位置" description="切换在重启应用后生效。远程可用时只由服务器执行日报和邮件任务；远程不可用时本次启动会自动回退到本机后端。">
        <SettingsRow
          label="当前模式"
          description={connection?.mode === 'remote' ? '远程服务器负责 Agent、数据与自动任务；本机保留桌面提醒和使用状态采样。启动时连接超过 8 秒会自动切换到本机数据，重启后重新尝试远程连接。' : connection?.mode !== 'remote' && connection?.remoteUrl ? '远程服务器启动时不可达，本次已自动切换到本机数据；重启后会重新尝试远程连接。' : connection?.mode === 'sidecar' ? '应用在本机启动独立后端。' : '开发模式连接本机 8081 端口。'}
          control={<span className={styles.mode}>{connection?.mode === 'remote' ? '远程服务器' : connection?.remoteUrl ? '本机后端（自动降级）' : connection?.mode === 'sidecar' ? '本机打包后端' : '本机开发后端'}</span>}
        />
        <SettingsRow
          label="服务器 API 地址"
          description="例如 http://工作室服务器局域网地址:8081。服务器需要允许桌面端来源跨域访问。"
          labelFor="remote-backend-url"
          control={<TextInput id="remote-backend-url" value={url} onChange={(event) => setUrl(event.target.value)} placeholder="http://192.168.1.10:8081" autoComplete="url" />}
        />
        <SettingsRow
          label="API Token"
          description={connection?.hasApiToken ? '系统钥匙串中已有 Token；重新保存时请输入新 Token。' : '填写服务器配置的 BUTVAN_API_TOKEN，至少 32 个字符。Token 保存到本机系统钥匙串。'}
          labelFor="remote-backend-token"
          control={<TextInput id="remote-backend-token" type="password" value={token} onChange={(event) => setToken(event.target.value)} placeholder={connection?.hasApiToken ? '已安全保存，输入新 Token 可替换' : '服务器 API Token'} autoComplete="new-password" />}
        />
        <div className={styles.actions}>
          <Button variant="outline" onClick={() => void test()} disabled={busy || !url.trim()}>测试连接</Button>
          <Button variant="primary" onClick={() => void save(url.trim())} disabled={busy || !url.trim() || token.trim().length < 32}>保存远程连接</Button>
          {connection?.remoteUrl && <Button variant="ghost" onClick={() => void save(null)} disabled={busy}>切回本机</Button>}
        </div>
      </SettingsGroup>
      <SettingsGroup title="设备提醒说明">
        <p className={styles.note}>日报、周报、月报和邮件由服务器持续执行。桌面通知、休息遮罩和久坐采样由本机 App 处理；电脑关机或 App 完全退出时，本机采样与提醒会暂停。未确认的普通桌面通知超过 15 分钟后会过期跳过，待确认提醒会保留到你处理。</p>
      </SettingsGroup>
      <SettingsGroup title="远程访问安全">
        <p className={styles.note}>服务器必须开启令牌校验（BUTVAN_API_REQUIRE_TOKEN=true），并限制防火墙只允许你的设备访问。当前业务仍是单用户服务；请通过 HTTPS 或可信 VPN 连接，不要把 8081 端口开放到公网。</p>
      </SettingsGroup>
    </SettingsPageLayout>
  );
}
