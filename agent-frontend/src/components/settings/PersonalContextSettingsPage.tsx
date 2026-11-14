import { useEffect, useState } from 'react';
import { BrainCircuit, Database, FileText, PauseCircle } from 'lucide-react';
import {
  fetchPersonalContext, fetchProfileMaintenance, updatePersonalContextEnabled,
} from '../../services/personalContextService';
import type { PersonalContextSettings, ProfileMaintenanceStatus } from '../../types/personalContext';
import { Button } from '../common/Button';
import { LeverSwitch } from '../common/LeverSwitch';
import { useMessage } from '../common/Message';
import { formatTokenCount } from '../chat/tokenUsageFormat';
import { AgentResetSection } from '../reset/AgentResetSection';
import { ExperienceMemorySettings } from '../memory/ExperienceMemorySettings';
import { PersonalProfileSection } from './PersonalProfileSection';
import { SettingsPageLayout } from './SettingsPageLayout';
import styles from './PersonalContextSettingsPage.module.css';

/** 用户可控的稳定画像、相关记忆注入与辅助维护设置。 */
export function PersonalContextSettingsPage() {
  const { showMessage } = useMessage();
  const [settings, setSettings] = useState<PersonalContextSettings | null>(null);
  const [maintenance, setMaintenance] = useState<ProfileMaintenanceStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const applyMaintenance = (next: ProfileMaintenanceStatus) => {
    setMaintenance(next);
    setSettings((current) => current ? { ...current, maintenanceEnabled: next.enabled } : current);
  };
  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [context, maintenanceStatus] = await Promise.all([fetchPersonalContext(), fetchProfileMaintenance()]);
      setSettings(context);
      applyMaintenance(maintenanceStatus);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取个人上下文失败');
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(); }, []);

  const mutateSettings = async (operation: () => Promise<PersonalContextSettings>, success: string) => {
    setSaving(true); setError(null);
    try {
      setSettings(await operation());
      applyMaintenance(await fetchProfileMaintenance());
      showMessage('success', success);
    } catch (cause) {
      showMessage('error', cause instanceof Error ? cause.message : '保存个人上下文失败');
    } finally { setSaving(false); }
  };

  if (loading && !settings) return <SettingsPageLayout title="个人上下文" description="控制 Agent 自动了解你的方式。" loading loadingLabel="正在读取本地设置…"><div className={styles.state} /></SettingsPageLayout>;
  if (!settings || !maintenance) return <SettingsPageLayout title="个人上下文" description="控制 Agent 自动了解你的方式。"><div className={styles.state} role="alert"><p>{error}</p><Button variant="outline" onClick={() => void load()}>重新加载</Button></div></SettingsPageLayout>;

  return <SettingsPageLayout title="个人上下文" description="管理 Agent 对你的了解和记忆。">
    <div className={styles.content}>
      <section className={styles.switchRow} aria-labelledby="context-switch-title">
        <div className={styles.switchCopy}><span className={styles.icon}><BrainCircuit size={18} aria-hidden="true" /></span><div><h2 id="context-switch-title">自动使用个人上下文</h2><p>{settings.enabled ? '回答时参考你的个人信息和相关经历。' : '已暂停使用，已有个人信息和记忆不会删除。'}</p></div></div>
        <LeverSwitch checked={settings.enabled} disabled={saving} label={settings.enabled ? '已开启' : '已暂停'} onChange={(enabled) => void mutateSettings(() => updatePersonalContextEnabled(enabled), enabled ? '已开启个人上下文' : '已暂停个人上下文')} />
      </section>

      <dl className={styles.budgets} aria-label="单次调用 Token 硬预算">
        <div><dt><FileText size={14} />个人画像</dt><dd>≤ {formatTokenCount(settings.profileTokenBudget)}</dd></div>
        <div><dt><Database size={14} />相关记忆</dt><dd>≤ {formatTokenCount(settings.memoryTokenBudget)}</dd></div>
        <div><dt><PauseCircle size={14} />合计上限</dt><dd>≤ {formatTokenCount(settings.totalTokenBudget)}</dd></div>
      </dl>

      <PersonalProfileSection
        settings={settings}
        maintenance={maintenance}
        saving={saving}
        showMaintenanceToggle
        onSettingsChange={setSettings}
        onMaintenanceChange={applyMaintenance}
      />

      <ExperienceMemorySettings />
      <AgentResetSection />
      <p className={styles.privacy}>个人信息和更新建议保存在本机，暂停使用不会删除。</p>
    </div>
  </SettingsPageLayout>;
}
