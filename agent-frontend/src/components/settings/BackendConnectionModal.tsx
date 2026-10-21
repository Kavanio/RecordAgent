import { useState } from 'react';
import { Modal } from '../common/Modal';
import { FormField } from '../common/FormField';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { getSavedBackendToken, saveBackendConnection, testBackendConnection, type BackendConnectionInfo } from '../../services/api';
import styles from './BackendConnectionModal.module.css';

interface BackendConnectionModalProps {
  connection: BackendConnectionInfo;
  onClose: () => void;
  onSaved: (changed: boolean, remoteUrl: string | null) => void;
}

/** 每次打开创建独立草稿；仅保存配置，不改变当前 API 和 SSE 连接。 */
export function BackendConnectionModal({ connection, onClose, onSaved }: BackendConnectionModalProps) {
  const [mode, setMode] = useState(connection.savedRemoteUrl ? 'remote' : 'local');
  const [url, setUrl] = useState(connection.savedRemoteUrl ?? connection.remoteUrl ?? '');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const { showMessage } = useMessage();
  const remote = mode === 'remote';
  const valid = !remote || (Boolean(url.trim()) && (token.trim().length >= 32 || (!token.trim() && connection.hasApiToken)));

  const run = async (save: boolean) => {
    setBusy(true);
    try {
      const remoteUrl = remote ? url.trim().replace(/\/+$/, '') : null;
      const apiToken = remote ? token.trim() || await getSavedBackendToken() : null;
      if (!save) {
        await testBackendConnection(remoteUrl!, apiToken);
        showMessage('success', '后端连接成功');
        return;
      }
      const changed = await saveBackendConnection(remoteUrl, apiToken);
      showMessage('success', changed ? '连接配置已保存，重启应用后生效' : connection.restartRequired ? '连接配置未变化，之前保存的配置仍需重启生效' : '连接配置未变化，无需重启');
      onSaved(changed, remoteUrl);
    } catch (error) {
      showMessage('error', error instanceof Error ? error.message : typeof error === 'string' ? error : '后端连接操作失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open title="配置后端连接" centered width={520} busy={busy} busyLabel="正在处理连接配置" onClose={onClose}>
      <form className={styles.form} onSubmit={(event) => { event.preventDefault(); if (valid && !busy) void run(true); }}>
        <FormField label="运行位置" htmlFor="backend-location" hint="保存不会立即切换当前连接，需重启应用后生效。">
          <Select id="backend-location" value={mode} onChange={(event) => setMode(event.target.value)} options={[{ label: '本机后端', value: 'local' }, { label: '远程服务器', value: 'remote' }]} fullWidth />
        </FormField>
        {remote && <>
          <FormField label="服务器 API 地址" htmlFor="remote-backend-url" hint="填写 HTTP/HTTPS 地址；服务器需要允许桌面端来源跨域访问。">
            <TextInput id="remote-backend-url" value={url} onChange={(event) => setUrl(event.target.value)} placeholder="http://192.168.1.10:8081" autoComplete="url" />
          </FormField>
          <FormField label="API Token" htmlFor="remote-backend-token" hint={connection.hasApiToken ? '留空沿用系统钥匙串中的 Token，填写至少 32 个字符可替换。' : '填写服务器的 BUTVAN_API_TOKEN，至少 32 个字符；保存到本机系统钥匙串。'}>
            <TextInput id="remote-backend-token" type="password" value={token} onChange={(event) => setToken(event.target.value)} placeholder={connection.hasApiToken ? '已安全保存，留空保持不变' : '服务器 API Token'} autoComplete="new-password" />
          </FormField>
        </>}
        <div className={styles.actions}>
          {remote && <Button type="button" variant="outline" disabled={busy || !valid} onClick={() => void run(false)}>测试连接</Button>}
          <Button type="button" variant="ghost" disabled={busy} onClick={onClose}>取消</Button>
          <Button type="submit" variant="primary" disabled={busy || !valid}>保存配置</Button>
        </div>
      </form>
    </Modal>
  );
}
