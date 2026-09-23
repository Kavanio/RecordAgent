import { useState, useEffect, type FormEvent } from 'react';
import type { SaveTaskMailSettings } from '../../types/automation';
import { Modal } from '../common/Modal';
import { FormField } from '../common/FormField';
import { TextInput } from '../common/TextInput';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import styles from './TaskMailConfigModal.module.css';

interface TaskMailConfigModalProps {
  open: boolean;
  onClose: () => void;
  initialSettings: SaveTaskMailSettings;
  recipient: string | null;
  onSave: (settings: SaveTaskMailSettings) => Promise<void>;
  onTest: () => Promise<{ status: string; message: string }>;
}

export function TaskMailConfigModal({
  open,
  onClose,
  initialSettings,
  recipient,
  onSave,
  onTest,
}: TaskMailConfigModalProps) {
  const [draft, setDraft] = useState<SaveTaskMailSettings>(initialSettings);
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const { showMessage } = useMessage();

  useEffect(() => {
    if (open) {
      setDraft(initialSettings);
    }
  }, [open, initialSettings]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!draft.host.trim()) {
      showMessage('error', '请输入 SMTP 服务器地址');
      return;
    }
    setBusy(true);
    try {
      await onSave(draft);
      showMessage('success', '邮件设置已保存');
      onClose();
    } catch (err) {
      showMessage('error', err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  const handleTest = async () => {
    if (!recipient) {
      showMessage('error', '尚未绑定收件邮箱，请先在任务设置中绑定收件邮箱');
      return;
    }
    setTesting(true);
    try {
      await onSave(draft);
      const res = await onTest();
      showMessage(
        res.status === 'SUBMITTED' ? 'success' : 'error',
        res.status === 'SUBMITTED' ? '测试邮件已提交，请检查收件箱' : res.message
      );
    } catch (err) {
      showMessage('error', err instanceof Error ? err.message : '测试邮件发送失败');
    } finally {
      setTesting(false);
    }
  };

  return (
    <Modal open={open} title="配置发件服务" onClose={onClose} width={540} centered>
      <form className={styles.form} onSubmit={handleSubmit}>
        <p className={styles.description}>
          使用 STARTTLS 加密。认证凭据仅保存在本机，留空保持原凭据。
        </p>
        <div className={styles.grid2}>
          <FormField label="SMTP 服务器" htmlFor="mail-host" required>
            <TextInput
              id="mail-host"
              placeholder="smtp.example.com"
              value={draft.host}
              disabled={busy || testing}
              onChange={e => setDraft(d => ({ ...d, host: e.target.value }))}
              autoFocus
            />
          </FormField>
          <FormField label="端口" htmlFor="mail-port" required hint="通常为 587 或 465">
            <TextInput
              id="mail-port"
              type="number"
              min={1}
              max={65535}
              value={draft.port}
              disabled={busy || testing}
              onChange={e => setDraft(d => ({ ...d, port: Number(e.target.value) || 587 }))}
            />
          </FormField>
        </div>
        <div className={styles.grid2}>
          <FormField label="发信用户名" htmlFor="mail-username">
            <TextInput
              id="mail-username"
              placeholder="例如：RecordAgent"
              value={draft.username}
              disabled={busy || testing}
              onChange={e => setDraft(d => ({ ...d, username: e.target.value }))}
            />
          </FormField>
          <FormField label="发件邮箱" htmlFor="mail-from" required>
            <TextInput
              id="mail-from"
              type="email"
              placeholder="sender@example.com"
              value={draft.from}
              disabled={busy || testing}
              onChange={e => setDraft(d => ({ ...d, from: e.target.value }))}
            />
          </FormField>
        </div>
        <FormField
          label="SMTP 认证凭据"
          htmlFor="mail-password"
          hint={draft.auth ? '已配置凭据；留空保持原凭据' : '输入邮箱授权码或应用密码'}
        >
          <TextInput
            id="mail-password"
            type="password"
            autoComplete="new-password"
            placeholder={draft.auth ? '••••••••••••' : '输入授权码或密码'}
            value={draft.password}
            disabled={busy || testing}
            onChange={e => setDraft(d => ({ ...d, password: e.target.value }))}
          />
        </FormField>
        <div className={styles.actions}>
          <Button
            type="button"
            variant="outline"
            disabled={busy || testing || !recipient || !draft.host.trim()}
            onClick={handleTest}
          >
            {testing ? '正在发送…' : '发送测试邮件'}
          </Button>
          <div className={styles.submitActions}>
            <Button type="button" variant="ghost" disabled={busy || testing} onClick={onClose}>
              取消
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={busy || testing || !draft.host.trim()}
            >
              {busy ? '正在保存…' : '保存邮件设置'}
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  );
}
