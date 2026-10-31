import type { LifeSettings } from '../../types/lifeReview';
import { Select } from '../common/Select';
import { TextInput } from '../common/TextInput';
import { SettingsRow } from '../settings/SettingsGroup';
import styles from './LifeReviewSettings.module.css';

const IDLE_CHECKIN_OPTIONS = [
  { value: '0', label: '关闭（不主动问候）' },
  { value: '2', label: '连续 2 天无互动后问候一次' },
  { value: '3', label: '连续 3 天无互动后问候一次' },
  { value: '5', label: '连续 5 天无互动后问候一次' },
  { value: '7', label: '连续 7 天无互动后问候一次' },
];

interface LifeSignalSettingsRowsProps {
  settings: LifeSettings;
  busy: boolean;
  patch: (changes: Partial<LifeSettings>) => void;
}

/** 主动呈现、飞书投递、久未问候与现实信息源提醒配置行。 */
export function LifeSignalSettingsRows({ settings: s, busy, patch }: LifeSignalSettingsRowsProps) {
  return (
    <>
      <SettingsRow
        label="主动呈现"
        description="冷启动每天最多一次；同主题无新信息不重复，23:30后顺延到次日中午。"
        control={
          <input
            aria-label="主动呈现生活复盘"
            type="checkbox"
            checked={s.proactive}
            disabled={busy}
            onChange={e => patch({
              proactive: e.target.checked,
              idleCheckinDays: e.target.checked ? s.idleCheckinDays : 0,
              weatherAlertEnabled: e.target.checked ? s.weatherAlertEnabled : false,
              expenseAlertEnabled: e.target.checked ? s.expenseAlertEnabled : false,
              serverAlertEnabled: e.target.checked ? s.serverAlertEnabled : false,
            })}
          />
        }
      />
      <SettingsRow
        label="投递到本人飞书"
        description={`${s.channelLabel}；目标只取已配置的本人白名单。飞书已读状态未知。`}
        control={
          <input
            aria-label="生活复盘飞书投递"
            type="checkbox"
            checked={s.feishu}
            disabled={busy || !s.channelAvailable}
            onChange={e => patch({
              feishu: e.target.checked,
              idleCheckinDays: e.target.checked ? s.idleCheckinDays : 0,
              weatherAlertEnabled: e.target.checked ? s.weatherAlertEnabled : false,
              expenseAlertEnabled: e.target.checked ? s.expenseAlertEnabled : false,
              serverAlertEnabled: e.target.checked ? s.serverAlertEnabled : false,
            })}
          />
        }
      />
      <SettingsRow
        label="久未互动飞书问候"
        description="需同时开启主动呈现与本人飞书。连续 N 天无聊天或手记互动时发送一句固定温和问候（不调用模型，未回复绝不催促）。"
        control={
          <Select
            aria-label="久未互动问候天数"
            value={String(s.idleCheckinDays || 0)}
            disabled={busy || !s.proactive || !s.feishu}
            options={IDLE_CHECKIN_OPTIONS}
            onChange={e => patch({ idleCheckinDays: Number(e.target.value) })}
          />
        }
      />
      <SettingsRow
        label="天气显著变化提醒"
        description="复用已配置的和风天气缓存（08:00—22:00），恶劣降水、大幅降温或天气转好时飞书提醒，不后台定位、不调用模型。"
        control={
          <input
            aria-label="天气显著变化提醒"
            type="checkbox"
            checked={Boolean(s.weatherAlertEnabled)}
            disabled={busy || !s.proactive || !s.feishu}
            onChange={e => patch({ weatherAlertEnabled: e.target.checked })}
          />
        }
      />
      <SettingsRow
        label="支出显著变化提醒"
        description="需勾选财务数值来源并开启主动飞书。当日支出显著高于近 28 天活跃日均基线时飞书客观提醒，同日同币种去重。"
        control={
          <input
            aria-label="支出显著变化提醒"
            type="checkbox"
            checked={Boolean(s.expenseAlertEnabled)}
            disabled={busy || !s.proactive || !s.feishu || !s.sources.includes('FINANCE')}
            onChange={e => patch({ expenseAlertEnabled: e.target.checked })}
          />
        }
      />
      <SettingsRow
        label="服务器健康探针提醒"
        description="只读定时 GET 检查健康接口（不授予 SSH、重启或执行命令权限），异常告警去重，恢复时单次通知。"
        layout={s.serverAlertEnabled ? 'stacked' : undefined}
        control={
          <div className={s.serverAlertEnabled ? styles.stack : undefined}>
            <label>
              <input
                aria-label="服务器只读健康提醒"
                type="checkbox"
                checked={Boolean(s.serverAlertEnabled)}
                disabled={busy || !s.proactive || !s.feishu}
                onChange={e => patch({ serverAlertEnabled: e.target.checked })}
              />
              开启健康探针检测
            </label>
            {s.serverAlertEnabled && (
              <div className={styles.stack}>
                <TextInput
                  aria-label="服务器标识名称"
                  value={s.serverProbeName || ''}
                  placeholder="服务器标识（默认：应用服务器）"
                  disabled={busy}
                  maxLength={50}
                  onChange={e => patch({ serverProbeName: e.target.value })}
                />
                <TextInput
                  aria-label="服务器探针地址"
                  value={s.serverProbeUrl || ''}
                  placeholder="只读健康检查接口（例如：https://api.example.com/actuator/health）"
                  disabled={busy}
                  maxLength={300}
                  onChange={e => patch({ serverProbeUrl: e.target.value })}
                />
              </div>
            )}
          </div>
        }
      />
    </>
  );
}
