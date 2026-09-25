import React, { useEffect, useState } from 'react';
import { FormField } from '../common/FormField';
import { Modal } from '../common/Modal';
import { TextInput } from '../common/TextInput';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { bindEmailAccount, sendEmailVerificationCode } from '../../services/api';
import styles from './EmailBindingModal.module.css';

interface EmailBindingModalProps {
  open: boolean;
  onClose: () => void;
  onBound: (maskedEmail: string | null) => void;
}

/** 完成当前设备邮箱验证与绑定的表单。 */
export const EmailBindingModal: React.FC<EmailBindingModalProps> = ({ open, onClose, onBound }) => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [verificationCode, setVerificationCode] = useState('');
  const { showMessage } = useMessage();
  const [sending, setSending] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [countdown, setCountdown] = useState(0);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setTimeout(() => setCountdown((seconds) => seconds - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [countdown]);

  const sendCode = async () => {
    if (!email.trim()) {
      showMessage('error', '请先输入邮箱地址');
      return;
    }
    setSending(true);
    const result = await sendEmailVerificationCode(email);
    setSending(false);
    if (result.success) setCountdown(60);
    showMessage(result.success ? 'success' : 'error', result.message);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (password !== confirmation) {
      showMessage('error', '两次输入的密码不一致');
      return;
    }
    setSubmitting(true);
    const result = await bindEmailAccount({ email, password, verificationCode });
    setSubmitting(false);
    if (!result.success || !result.data) {
      showMessage('error', result.message);
      return;
    }
    onBound(result.data.maskedEmail);
    showMessage('success', '邮箱绑定成功');
    onClose();
  };

  return (
    <Modal open={open} title="绑定邮箱" onClose={onClose} width={440}>
      <form onSubmit={submit} className={styles.form}>
        <p className={styles.description}>绑定后，Agent 可以按你的通知设置向此邮箱发送提醒。</p>
        <FormField label="邮箱" htmlFor="account-email" required>
          <TextInput id="account-email" type="email" autoComplete="email" value={email}
            onChange={(event) => setEmail(event.target.value)} placeholder="name@example.com" required />
        </FormField>
        <FormField label="设置密码" htmlFor="account-password" required>
          <TextInput id="account-password" type="password" autoComplete="new-password" minLength={8}
            value={password} onChange={(event) => setPassword(event.target.value)} placeholder="至少 8 位" required />
        </FormField>
        <FormField label="确认密码" htmlFor="account-password-confirmation" required>
          <TextInput id="account-password-confirmation" type="password" autoComplete="new-password" minLength={8}
            value={confirmation} onChange={(event) => setConfirmation(event.target.value)} placeholder="再次输入密码" required />
        </FormField>
        <FormField label="邮箱验证码" htmlFor="account-verification-code" required>
          <div className={styles.codeRow}>
            <TextInput id="account-verification-code" inputMode="numeric" maxLength={6} value={verificationCode}
              onChange={(event) => setVerificationCode(event.target.value.replace(/\D/g, ''))} placeholder="6 位验证码" required />
            <Button variant="outline" type="button" onClick={sendCode} disabled={sending || countdown > 0}>
              {sending ? '发送中…' : countdown > 0 ? `${countdown}s 后重发` : '获取验证码'}
            </Button>
          </div>
        </FormField>
        <div className={styles.actions}>
          <Button type="button" variant="outline" onClick={onClose}>取消</Button>
          <Button type="submit" variant="primary" disabled={submitting}>
            {submitting ? '绑定中…' : '注册并绑定'}
          </Button>
        </div>
      </form>
    </Modal>
  );
};
