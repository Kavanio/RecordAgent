import { useEffect, useState } from 'react';
import type { SaveTaskMailSettings } from '../../types/automation';
import { fetchTaskMailSettings, saveTaskMailSettings, testTaskMail } from '../../services/automationApi';
import {
  getTaskBackground,
  isTaskDesktop,
  requestTaskNotificationPermission,
  taskNotificationStatus,
  setTaskBackground,
} from '../../services/taskDesktop';
import { SettingsGroup, SettingsRow } from '../settings/SettingsGroup';
import { SettingsPageLayout } from '../settings/SettingsPageLayout';
import { LeverSwitch } from '../common/LeverSwitch';
import { Button } from '../common/Button';
import { useMessage } from '../common/Message';
import { EmailBindingModal } from '../account/EmailBindingModal';
import { TaskMailConfigModal } from './TaskMailConfigModal';
import styles from './TaskSettings.module.css';

export function TaskSettings() {
  const [form, setForm] = useState<SaveTaskMailSettings | null>(null);
  const [recipient, setRecipient] = useState<string | null>(null);
  const [background, setBackground] = useState(false);
  const [notificationGranted, setNotificationGranted] = useState(false);
  const [notificationMode, setNotificationMode] = useState('NATIVE');
  const [notificationDetails, setNotificationDetails] = useState('');
  const [binding, setBinding] = useState(false);
  const [mailModalOpen, setMailModalOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const { showMessage } = useMessage();

  async function load() {
    setError('');
    try {
      const value = await fetchTaskMailSettings();
      setForm({
        host: value.host,
        port: value.port,
        username: value.username,
        from: value.from,
        auth: value.auth,
        starttls: true,
        password: '',
        enabled: value.enabled,
      });
      setRecipient(value.maskedRecipient);
      setBackground(await getTaskBackground());
      const notification = await taskNotificationStatus();
      setNotificationGranted(notification.authorized);
      setNotificationMode(notification.mode);
      setNotificationDetails(notification.mode === 'DEV_FALLBACK' ? '开发模式以提醒窗口和系统提示音呈现；安装版使用 macOS 通知中心'
        : notification.authorized ? `横幅${notification.alertEnabled ? '已开启' : '未开启'}，声音${notification.soundEnabled ? '已开启' : '未开启'}` : '请允许本应用显示通知');
    } catch (e) {
      setError(e instanceof Error ? e.message : '配置读取失败');
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function action(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败');
    } finally {
      setBusy(false);
    }
  }

  const handleSaveMail = async (nextSettings: SaveTaskMailSettings) => {
    await saveTaskMailSettings(nextSettings);
    setForm({ ...nextSettings, password: '' });
  };

  return (
    <SettingsPageLayout title="任务设置" description="管理任务通知、邮箱与后台运行。">
      <div className={styles.body}>
        <SettingsGroup title="桌面提醒" description="应用完全退出后，定时任务与邮件发送停止。">
          <SettingsRow
            label={notificationMode === 'DEV_FALLBACK' ? '开发模式提醒' : '系统通知权限'}
            description={notificationDetails || '由你主动授权，系统专注模式仍可能抑制通知'}
            control={
              <LeverSwitch
                checked={notificationGranted}
                disabled={busy || !isTaskDesktop() || notificationMode === 'DEV_FALLBACK'}
                label={notificationMode === 'DEV_FALLBACK' ? '开发模式提醒' : '系统通知权限'}
                showLabel={false}
                onChange={async (checked) => {
                  if (checked) {
                    await action(async () => {
                      const granted = await requestTaskNotificationPermission();
                      setNotificationGranted(granted);
                      const notification = await taskNotificationStatus();
                      setNotificationDetails(granted
                        ? `横幅${notification.alertEnabled ? '已开启' : '未开启'}，声音${notification.soundEnabled ? '已开启' : '未开启'}`
                        : '请在系统设置中允许本应用显示通知');
                      showMessage(
                        granted ? 'success' : 'info',
                        granted ? '系统通知已允许' : '通知尚未允许，请在系统设置中授权'
                      );
                    });
                  } else {
                    showMessage('info', '系统通知权限由系统设置控制，如需关闭请前往系统偏好设置');
                  }
                }}
              />
            }
          />
          <SettingsRow
            label="关闭主窗口后在后台运行"
            description="可从菜单栏图标重新打开或彻底退出"
            control={
              <LeverSwitch
                checked={background}
                disabled={busy || !isTaskDesktop()}
                label="关闭主窗口后在后台运行"
                showLabel={false}
                onChange={(value) => {
                  void action(async () => {
                    await setTaskBackground(value);
                    setBackground(value);
                    showMessage('success', value ? '已开启后台运行' : '已关闭后台运行');
                  });
                }}
              />
            }
          />
        </SettingsGroup>

        <SettingsGroup title="收件邮箱" description="第一版仅向当前已验证邮箱发送。">
          <SettingsRow
            label={recipient ?? '尚未绑定邮箱'}
            description="先配置 SMTP，再通过验证码绑定邮箱"
            control={
              <Button disabled={busy} onClick={() => setBinding(true)}>
                绑定邮箱
              </Button>
            }
          />
          {form && (
            <SettingsRow
              label="允许任务发送邮件"
              description="关闭后不会发送新的任务邮件"
              control={
                <LeverSwitch
                  checked={form.enabled}
                  disabled={busy}
                  label="允许任务发送邮件"
                  showLabel={false}
                  onChange={(checked) => {
                    const next = { ...form, enabled: checked };
                    setForm(next);
                    void action(async () => {
                      await saveTaskMailSettings({ ...next, password: '' });
                      showMessage('success', checked ? '已允许任务发送邮件' : '已暂停任务发送邮件');
                    });
                  }}
                />
              }
            />
          )}
        </SettingsGroup>

        {form && (
          <SettingsGroup
            title="发件服务"
            description="使用 STARTTLS 加密。认证凭据仅保存在本机，留空保持原凭据。"
          >
            <SettingsRow
              label={form.host || '尚未配置发件服务'}
              description={
                form.host
                  ? `端口 ${form.port} · 发信邮箱 ${form.from || '未设置'}`
                  : '配置后可通过邮件接收每日提醒与任务日报'
              }
              control={
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  onClick={() => setMailModalOpen(true)}
                >
                  {form.host ? '配置发件服务' : '设置发件服务'}
                </Button>
              }
            />
          </SettingsGroup>
        )}

        {error && (
          <p role="alert" className={styles.error}>
            {error}
            <Button variant="ghost" onClick={() => void load()}>
              重新读取
            </Button>
          </p>
        )}

        {binding && (
          <EmailBindingModal
            open
            onClose={() => {
              setBinding(false);
              void load();
            }}
            onBound={() => {
              setBinding(false);
              void load();
            }}
          />
        )}

        {form && (
          <TaskMailConfigModal
            open={mailModalOpen}
            onClose={() => setMailModalOpen(false)}
            initialSettings={form}
            recipient={recipient}
            onSave={handleSaveMail}
            onTest={testTaskMail}
          />
        )}
      </div>
    </SettingsPageLayout>
  );
}
