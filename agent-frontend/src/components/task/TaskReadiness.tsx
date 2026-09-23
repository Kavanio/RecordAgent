import { useEffect, useState } from 'react';
import { fetchTaskMailSettings } from '../../services/automationApi';
import { taskNotificationStatus, getTaskBackground, isTaskDesktop } from '../../services/taskDesktop';
import { Button } from '../common/Button';
import styles from './TaskReadiness.module.css';

/** 独立读取运行条件；某项失败不阻断其余条件或任务列表。 */
export function TaskReadiness({ onOpenSettings }: { onOpenSettings: () => void }) {
  const [status, setStatus] = useState({ background: '读取中', notification: '读取中', mail: '读取中' });
  useEffect(() => {
    let stopped = false;
    async function load() {
      const [background, notification, mail] = await Promise.allSettled([
        getTaskBackground(), taskNotificationStatus(), fetchTaskMailSettings(),
      ]);
      if (stopped) return;
      setStatus({
        background: !isTaskDesktop() ? '浏览器模式' : background.status === 'fulfilled' ? background.value ? '已开启' : '未开启' : '读取失败',
        notification: !isTaskDesktop() ? '仅应用内' : notification.status === 'fulfilled'
          ? notification.value.mode === 'DEV_FALLBACK' ? '开发模式提醒窗'
            : !notification.value.authorized ? '未授权'
              : !notification.value.alertEnabled ? '横幅关闭'
                : !notification.value.soundEnabled ? '横幅已开启，声音关闭' : '横幅和声音已开启'
          : '读取失败',
        mail: mail.status === 'fulfilled' ? mail.value.ready ? '可用' : '未就绪' : '读取失败',
      });
    }
    void load();
    window.addEventListener('focus', load);
    return () => { stopped = true; window.removeEventListener('focus', load); };
  }, []);
  return <section className={styles.status} aria-label="任务运行条件">
    <div><span>后台运行：{status.background}</span><span>系统通知：{status.notification}</span><span>邮件：{status.mail}</span></div>
    <p>应用彻底退出后任务停止。安装版遵循 macOS 通知设置与专注模式；开发模式使用应用提醒窗和提示音。</p>
    <Button size="sm" variant="ghost" onClick={onOpenSettings}>配置运行条件</Button>
  </section>;
}
